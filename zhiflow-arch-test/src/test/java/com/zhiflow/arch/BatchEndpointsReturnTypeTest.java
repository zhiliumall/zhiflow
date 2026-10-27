package com.zhiflow.arch;

import com.zhiflow.arch.fixture.batch.BatchReturnSamples;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4 判据的反向校准：已知「含 BatchResult」的返回形态必须判真，否则 R4 会把合规端点报成违例（或更糟：被迫进豁免表）。
 */
class BatchEndpointsReturnTypeTest {

	private final JavaClass samples = new ClassFileImporter().importClass(BatchReturnSamples.class);

	@Test
	@DisplayName("R<BatchResult<X>>、R<BatchResult>、BatchResult<X> 都算含 BatchResult；R<String> 不算")
	void recognisesGenericAndRawForms() {
		assertTrue(BatchEndpoints.returnsBatchResult(samples.getMethod("wrappedGeneric")));
		assertTrue(BatchEndpoints.returnsBatchResult(samples.getMethod("wrappedRaw")));
		assertTrue(BatchEndpoints.returnsBatchResult(samples.getMethod("bare")));
		assertFalse(BatchEndpoints.returnsBatchResult(samples.getMethod("notBatch")));
	}
}
