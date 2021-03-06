package com.centurylink.mdw.common.translator.impl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.translator.DocumentReferenceTranslator;
import com.centurylink.mdw.translator.TranslationException;

@SuppressWarnings("unused")
public class StringStringMapTranslator extends DocumentReferenceTranslator {

    @Override
    public Object toObject(String str, String type) throws TranslationException {
        try {
            Map<String,String> stringMap = new HashMap<>();
            JSONObject jsonObject = new JsonObject(str);
            String[] stringNames = JSONObject.getNames(jsonObject);
            if (stringNames != null) {
                for (int i = 0; i < stringNames.length; i++) {
                    stringMap.put(stringNames[i], jsonObject.optString(stringNames[i], null));
                }
            }
            return stringMap;
        }
        catch (JSONException ex) {
            throw new TranslationException(ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public String toString(Object obj, String variableType) throws TranslationException {
        Map<String,String> stringMap = (Map<String,String>) obj;
        JSONObject jsonObject = new JsonObject();
        Iterator<String> it = stringMap.keySet().iterator();
        try {
            while (it.hasNext()) {
                String name = it.next();
                String val = stringMap.get(name);
                jsonObject.put(name, val == null ? JSONObject.NULL : val);
            }
        return jsonObject.toString(2);
        }
        catch (JSONException e) {
            throw new TranslationException(e.getMessage(), e);
        }
    }
}