package cc.huajistudio.aeb.strategy;

public enum EnumInvokerGenerator {	
	ASM,
	REFLECT;
	
	public static EnumInvokerGenerator getDefault() {		
		return ASM;
	}
}
