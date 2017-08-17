package cc.huajistudio.aeb.generator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import cc.huajistudio.aeb.EventInvoker;
import cc.huajistudio.aeb.exception.AEBRegisterException;

public class ReflectionInvokerGenerator implements InvokerGenerator {
	
	public EventInvoker generateInvoker(Class<?> handler,
                                        Method subscriber, Class<?> event) throws AEBRegisterException {
		if((subscriber.getModifiers() & Modifier.SYNCHRONIZED) > 0)
			return new EventInvoker.SyncReflectedEventInvoker(subscriber);
		else 
			return new EventInvoker.AsyncReflectedEventInvoker(subscriber);
	}
}
