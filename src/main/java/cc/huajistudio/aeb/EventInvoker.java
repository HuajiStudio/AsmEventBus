package cc.huajistudio.aeb;

import java.lang.reflect.Method;

public abstract class EventInvoker {
	
	protected final Method subscriber;
	
	public EventInvoker(Method subscriber) {
		this.subscriber = subscriber;
	}
	
	public Method getSubscriber() {
		return subscriber;
	}
	
	public abstract void invoke(Object receiver, Object event) throws Throwable;
	
	public static class AsyncReflectedEventInvoker extends EventInvoker{

		public AsyncReflectedEventInvoker(Method subscriber) {
			super(subscriber);
			subscriber.setAccessible(true);
		}
		@Override
		public void invoke(Object receiver, Object event) throws Throwable{
			subscriber.invoke(receiver, event);
		}
	}
	
	public static class SyncReflectedEventInvoker extends EventInvoker{

		public SyncReflectedEventInvoker(Method subscriber) {
			super(subscriber);
			subscriber.setAccessible(true);
		}
		@Override
		public synchronized void invoke(Object receiver, Object event) throws Throwable{
			subscriber.invoke(receiver, event);
		}
	}
	
	/*public static class WWW extends EventInvoker {

		public WWW(Method subscriber) {
			super(subscriber);
		}

		@Override
		public void invoke(Object receiver, Object event) {
			((EventBus)receiver).register(event);
		}
		
	}*/
}
