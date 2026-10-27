/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalHotWordJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void snowflakeIdIsWrittenAsStringAndReadBackExactly() {
		GlobalHotWord word = new GlobalHotWord();
		word.setId(1900000000000000004L);

		String json = mapper.writeValueAsString(word);

		assertThat(json).contains("\"id\":\"1900000000000000004\"");
		assertThat(mapper.readValue(json, GlobalHotWord.class).getId()).isEqualTo(1900000000000000004L);
	}

	@Test
	void numericIdFromOlderClientsIsStillAccepted() {
		assertThat(mapper.readValue("{\"id\":1900000000000000004}", GlobalHotWord.class).getId())
			.isEqualTo(1900000000000000004L);
	}
}
