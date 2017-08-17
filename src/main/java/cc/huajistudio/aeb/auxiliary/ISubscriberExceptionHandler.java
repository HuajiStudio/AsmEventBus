package cc.huajistudio.aeb.auxiliary;

import java.lang.reflect.Method;

import cc.huajistudio.aeb.EventBus;

public interface ISubscriberExceptionHandler {

	public abstract void handleSubscriberException(EventBus eventBus, Object handler, Method subscriber, Object event, Throwable e);
}
