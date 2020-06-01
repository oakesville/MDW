/*
 * Copyright (C) 2017 CenturyLink, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.centurylink.mdw.model.variable;

import com.centurylink.mdw.model.Jsonable;
import org.json.JSONException;
import org.json.JSONObject;

public class VariableType implements Jsonable {

    private final String name;
    public String getName() { return name; }

    private final String translatorClass;
    public String getTranslatorClass() { return translatorClass; }

    private final boolean document;
    public boolean isDocument() { return document; }

    /**
     * Unfortunately VARIABLE_INSTANCE keys on this int ID for inflights.
     */
    @Deprecated
    private Integer id;
    public Integer getId() {
        return id;
    }

    public VariableType(String name, String translatorClass, boolean document) {
        this.name = name;
        this.translatorClass = translatorClass;
        this.document = document;
    }

    @Deprecated
    public VariableType(String name, String translatorClass, boolean document, Integer id) {
        this(name, translatorClass, document);
        this.id = id;
    }

    public VariableType(JSONObject json) {
        this.name = json.getString("name");
        this.translatorClass = json.getString("translator");
        this.document = json.optBoolean("document", false);
    }

    public boolean isJavaObjectType() {
        return Object.class.getName().equals(name)
                || (translatorClass != null && translatorClass.endsWith("JavaObjectTranslator"));
    }

    // for Object types, true if SelfSerializable
    private boolean updateable = true;
    public boolean isUpdateable() { return updateable; }
    public void setUpdateable(boolean updateable) { this.updateable = updateable; }

    @Override
    public String toString() {
        return name + ": " + translatorClass;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VariableType && obj.toString().equals(toString());
    }

    @Override
    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        json.put("name", name);
        json.put("translator", translatorClass);
        if (isDocument())
            json.put("document", true);
        return json;
    }

    @Override
    public String getJsonName() {
        return "VariableType";
    }
}
