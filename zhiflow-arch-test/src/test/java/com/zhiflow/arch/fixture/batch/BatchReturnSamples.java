package com.zhiflow.arch.fixture.batch;

/** 四种返回形态的样本方法，供 {@code BatchEndpointsReturnTypeTest} 反向校准判据。 */
public class BatchReturnSamples {

	public R<BatchResult<String>> wrappedGeneric() {
		return null;
	}

	@SuppressWarnings("rawtypes")
	public R<BatchResult> wrappedRaw() {
		return null;
	}

	public BatchResult<Long> bare() {
		return null;
	}

	public R<String> notBatch() {
		return null;
	}
}
