package cc.huajistudio.aeb.auxiliary;

import cc.huajistudio.aeb.EventBus;

public interface IDeadEventHandler {

	public abstract void handleDeadEvent(EventBus eventBus, Object event);
}
