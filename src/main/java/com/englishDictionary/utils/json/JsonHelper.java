package com.englishDictionary.utils.json;

import org.codehaus.jackson.map.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created by Андрей on 04.03.15.
 */
public class JsonHelper {
    public static String toJson(Object obj) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream buffOS = new ByteArrayOutputStream();
        mapper.writeValue(buffOS, obj);
        return buffOS.toString("UTF-8");
    }

    public static final class Data {
        private Object data;

        public Data(Object data) {
            this.data = data;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }
}
