package cc.huajistudio.aeb.generator;

import java.lang.reflect.Method;

import cc.huajistudio.aeb.EventInvoker;
import cc.huajistudio.aeb.exception.AEBRegisterException;

public interface InvokerGenerator {
	public abstract EventInvoker generateInvoker(Class<?> handler, Method subscriber, Class<?> event) throws AEBRegisterException;
}
