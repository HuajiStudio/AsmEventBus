package net.hakugyokurou.aeb;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import net.hakugyokurou.aeb.auxiliary.IDeadEventHandler;
import net.hakugyokurou.aeb.auxiliary.ISubscriberExceptionHandler;
import net.hakugyokurou.aeb.exception.AEBRegisterException;
import net.hakugyokurou.aeb.quickstart.AnnotatedSubscriberFinder;
import net.hakugyokurou.aeb.quickstart.EventSubscriber;
import net.hakugyokurou.aeb.quickstart.LoggingSubscriberExceptionHandler;
import net.hakugyokurou.aeb.quickstart.DiscardDeadEventHandler;
import net.hakugyokurou.aeb.strategy.EnumHierarchyStrategy;
import net.hakugyokurou.aeb.strategy.ISubscriberStrategy;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class EventBus {
	
	private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);
	
	protected final transient int id;
	protected final String name;
	protected final EnumHierarchyStrategy hierarchyStrategy;
	protected final ISubscriberStrategy subscriberStrategy;
	protected final boolean baseOnInstance;
	
	protected IDeadEventHandler deadEventHandler;
	protected ISubscriberExceptionHandler exceptionHandler;
	protected Logger logger;
	
	protected static Map<Method, EventInvoker> eventInvokerCache = Collections.synchronizedMap(new WeakHashMap<Method, EventInvoker>(64));
	
	protected Map<Class<?>, EventDispatcher> eventMappingInvoker = new WeakHashMap<Class<?>, EventDispatcher>();
	protected ReadWriteLock eventMappingInvokerLock = new ReentrantReadWriteLock();
	
	protected Map<Object, Method[]> handlerMappingMethods = Collections.synchronizedMap(new WeakHashMap<Object, Method[]>());
	
	
	public EventBus() {
		this(null);
	}
	
	public EventBus(String name) {
		this(name, getDefaultSubscriberStrategy());
	}
	
	public EventBus(String name, ISubscriberStrategy subscriberStrategy) {
		this(name, EnumHierarchyStrategy.EXTENDED_FIRST, subscriberStrategy);
	}
	
	public EventBus(String name, EnumHierarchyStrategy hierarchyStrategy) {
		this(name, hierarchyStrategy, getDefaultSubscriberStrategy());
	}
	
	public EventBus(String name, EnumHierarchyStrategy hierarchyStrategy, ISubscriberStrategy subscriberStrategy) {
		this.id = ID_GENERATOR.getAndIncrement();
		this.name = name==null?getDefaultName():name;
		this.hierarchyStrategy = hierarchyStrategy;
		this.subscriberStrategy = subscriberStrategy;
		this.baseOnInstance = subscriberStrategy.isDependOnInstance();
	}
	
	protected synchronized static String getDefaultName() {
		return "EventBus"+(System.nanoTime()%10000L);
	}
	
	public static ISubscriberStrategy getDefaultSubscriberStrategy() {
		return AnnotatedSubscriberFinder.SINGLETON;
	}
	
	public static IDeadEventHandler getDefaultDeadEventHandler() {
		return DiscardDeadEventHandler.SINGLETON;
	}
	
	public static ISubscriberExceptionHandler getDefaultSubscriberExceptionHandler() {
		return LoggingSubscriberExceptionHandler.SINGLETON;
	}
	
	public static Logger getDefaultLogger(EventBus eventBus) {
		return Logger.getLogger(eventBus.getClass().getName()+"."+eventBus.getName());
	}
	
	public synchronized void setDeadEventHandler(IDeadEventHandler deadEventHandler) {
		this.deadEventHandler = deadEventHandler;
	}
	
	public synchronized void setSubscriberExceptionHandler(ISubscriberExceptionHandler exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}
	
	public synchronized void setLogger(Logger logger) {
		this.logger = logger;
	}
	
	public int getId() {
		return id;
	}
	
	public String getName() {
		return name;
	}
	
	public EnumHierarchyStrategy getHierarchyStrategy() {
		return hierarchyStrategy;
	}
	
	public ISubscriberStrategy getSubscriberStrategy() {
		return subscriberStrategy;
	}
	
	public synchronized IDeadEventHandler getDeadEventHandler() {
		if(deadEventHandler==null)
			deadEventHandler = getDefaultDeadEventHandler();
		return deadEventHandler;
	}
	
	public synchronized ISubscriberExceptionHandler getSubscriberExceptionHandler() {
		if(exceptionHandler==null)
			exceptionHandler = getDefaultSubscriberExceptionHandler();
		return exceptionHandler;
	}
	
	public synchronized Logger getLogger() {
		if(logger==null)
			logger = getDefaultLogger(this);
		return logger;
	}
	
	public void register(Object handler) {
		Class<?> klass = handler instanceof Class ? (Class)handler : handler.getClass();
		Method[] methods = subscriberStrategy.findSubscribers(handler);
		//EventInvoker[] invokers = new EventInvoker[methods.length];
		//int index=0;
		boolean hasError = false; //TODO:What should we do if there are wrong things?
		for(Method method : methods) {
			Class<?> event = method.getParameterTypes()[0];
			EventInvoker invoker = eventInvokerCache.get(method);
			if(invoker==null)
			{
				try {
					invoker = InvokerGenerator.generateInvoker(klass, method, event);
					eventInvokerCache.put(method, invoker);
					//invokers[index++] = invoker;
				} catch (AEBRegisterException e) {
					e.printStackTrace();
					hasError = true;
					continue;
				}
			}
			addReceiver(event, handler, method, invoker);
		}
		handlerMappingMethods.put(handler, methods);
	}
	
	protected void addReceiver(Class<?> event, Object handler, Method subscriber, EventInvoker invoker) {
		EventDispatcher dispatcher;
		eventMappingInvokerLock.readLock().lock();
		try {
			dispatcher = eventMappingInvoker.get(event);
		}
		finally {
			eventMappingInvokerLock.readLock().unlock();
		}
		if(dispatcher==null)
		{
			dispatcher = hierarchyStrategy==EnumHierarchyStrategy.EXTENDED_FIRST?
					new EventDispatcher.SPEventDispatcherEF(this, event):new EventDispatcher.SPEventDispatcherSF(this, event);
			eventMappingInvokerLock.writeLock().lock();
			try {
				dealHierarchy(dispatcher);
				eventMappingInvoker.put(event, dispatcher);
			}
			finally {
				eventMappingInvokerLock.writeLock().unlock();
			}
		}
		dispatcher.addReceiver(handler, invoker);
	}
	
	protected final void dealHierarchy(EventDispatcher dispatcher) {
		for(EventDispatcher o2 : eventMappingInvoker.values())
		{
			if(o2.isSuper(dispatcher))
			{
				if(dispatcher.getParent()==null || o2.isSuper(dispatcher.getParent()))
				{
					dispatcher.setParent(o2);
				}
			}
			else if(dispatcher.isSuper(o2))
			{
				if(o2.getParent()==null  || dispatcher.isSuper(o2.getParent()))
				{
					o2.setParent(dispatcher);
				}
			}
				
		}
	}
	
	protected final void repairHierarchy(EventDispatcher dispatcher) {
		eventMappingInvokerLock.writeLock().lock();
		try {
			dealHierarchy(dispatcher);
		}
		finally {
			eventMappingInvokerLock.writeLock().unlock();
		}
	}
	
	public void unregister(Object handler) {
		Class<?> klass = handler instanceof Class ? (Class)handler : handler.getClass();
		Method[] methods = handlerMappingMethods.get(handler);
		if(methods==null)
		{
			//TODO:DO SOMETHINGS
			return;
		}
		boolean hasError = false;
		for(Method method : methods) {
			Class<?> event = method.getParameterTypes()[0];
			EventInvoker invoker = eventInvokerCache.get(method);
			if(invoker==null)
			{
				continue;
			}
			EventDispatcher dispatcher;
			eventMappingInvokerLock.readLock().lock();
			try {
				dispatcher = eventMappingInvoker.get(event);
			}
			finally {
				eventMappingInvokerLock.readLock().unlock();
			}
			if(dispatcher==null)
			{
				continue;
			}
			dispatcher.removeReceiver(handler, invoker);
		}
		//TODO:This method is a big sucker, fix it on some day.
	}
	
	public void post(Object event) {
		EventDispatcher dispatcher;
		eventMappingInvokerLock.readLock().lock();
		try {
			dispatcher = eventMappingInvoker.get(event.getClass());
		}
		finally {
			eventMappingInvokerLock.readLock().unlock();
		}
		boolean dead = true;
		if(dispatcher!=null)
		{
			dead = !dispatcher.post(event);
		}
		if(dead)
		{
			getDeadEventHandler().handleDeadEvent(this, event);
		}
	}
	
	protected static class InvokerGenerator {
		
		protected static String CLASS_NAME_EventInvoker = EventInvoker.class.getName().replace('.', '/');
		//protected static String CLASS_NAME_Event = Event.class.getName().replace('.', '/');
		
		protected static String CONST_PARAMS = "(Ljava/lang/Object;Ljava/lang/Object;)V";
		protected static String CONST_LV = "Ljava/lang/Object;";
		
		public static EventInvoker generateInvoker(Class<?> handler, Method subscriber, Class<?> event) throws AEBRegisterException {
			String handlerName = handler.getName().replace('.', '/');
			String invokerName = handlerName+"_Invoker_"+subscriber.getName()+"_"+Math.abs(subscriber.hashCode());
			String invokerName2 = invokerName.replace('/', '.'); //Too silly...Someone makes it smart, please.
			String eventName = event.getName().replace('.', '/');
			
			ClassLoader cl = handler.getClassLoader();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, invokerName, null, CLASS_NAME_EventInvoker, null);
			{
				MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/reflect/Method;)V", null, null);
				mv.visitCode();
				Label l0 = new Label();
				mv.visitLabel(l0);
				mv.visitLineNumber(27, l0);
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CLASS_NAME_EventInvoker, "<init>", "(Ljava/lang/reflect/Method;)V", false);
				Label l1 = new Label();
				mv.visitLabel(l1);
				mv.visitLineNumber(28, l1);
				mv.visitInsn(Opcodes.RETURN);
				Label l2 = new Label();
				mv.visitLabel(l2);
				mv.visitLocalVariable("this", "L"+invokerName+";", null, l0, l2, 0);
				mv.visitLocalVariable("subscriber", "Ljava/lang/reflect/Method;", null, l0, l2, 1);
				mv.visitMaxs(2, 2);
				mv.visitEnd();
			}
			{
				MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", CONST_PARAMS, null, null);
				mv.visitCode();
				Label l0 = new Label();
				mv.visitLabel(l0);
				mv.visitLineNumber(7, l0);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitTypeInsn(Opcodes.CHECKCAST, handlerName);
				mv.visitVarInsn(Opcodes.ALOAD, 2);
				mv.visitTypeInsn(Opcodes.CHECKCAST, eventName);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, handlerName, subscriber.getName(), "(L"+eventName+";)V", false);
				Label l1 = new Label();
				mv.visitLabel(l1);
				mv.visitLineNumber(8, l1);
				mv.visitInsn(Opcodes.RETURN);
				Label l2 = new Label();
				mv.visitLabel(l2);
				mv.visitLocalVariable("this", "L"+invokerName+";", null, l0, l2, 0);
				mv.visitLocalVariable("receiver", "Ljava/lang/Object;", null, l0, l2, 1);
				mv.visitLocalVariable("event", CONST_LV, null, l0, l2, 2);
				mv.visitMaxs(2, 3);
				mv.visitEnd();
			}
			cw.visitEnd();
			//Were these written by me? Fucking of course not. They were proudly generated by Bytecode Outline!
			byte[] bytes = cw.toByteArray();
			Method define = null;
			Object klass;
			boolean unaccessible = false;
			//XXX:In some case, this is NOT thread-safe. For example, an another thread changes the access of define while we are invoking...
			try {
				define = ClassLoader.class.getDeclaredMethod("defineClass", new Class[] {String.class, byte[].class, int.class, int.class} );
				unaccessible = !define.isAccessible();
				if(unaccessible)
					define.setAccessible(true);
				klass = ((Class<?>)define.invoke(cl, invokerName2,bytes,0,bytes.length)).getConstructor(Method.class).newInstance(subscriber);
			} catch (Exception e) {
				throw new AEBRegisterException(e);
			} finally {
				if(unaccessible)
					define.setAccessible(false);
			}
			return (EventInvoker)klass;
		}
	}
	
	

}
