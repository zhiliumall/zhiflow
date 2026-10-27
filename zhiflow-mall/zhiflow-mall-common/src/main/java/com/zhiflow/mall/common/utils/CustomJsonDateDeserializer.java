package com.zhiflow.mall.common.utils;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;


public class CustomJsonDateDeserializer extends ValueDeserializer<Date> {

    @Override
    public Date deserialize(JsonParser jp, DeserializationContext ctxt) {
        String date = jp.getString().substring(0,jp.getString().lastIndexOf(".")).replace("T", " ");
        try {
            return parse(date);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public Date parse(String dateStr) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateStr);
    }
}