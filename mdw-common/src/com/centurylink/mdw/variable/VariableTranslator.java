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
package com.centurylink.mdw.variable;

import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.translator.DocumentReferenceTranslator;
import com.centurylink.mdw.translator.TranslationException;

public interface VariableTranslator {

    String EMPTY_STRING = "<EMPTY>";

    /**
     * Serialize the given object to a string value
     */
    String toString(Object obj) throws TranslationException;

    /**
     * Deserialize an object from the given string
     */
    Object toObject(String str) throws TranslationException;

    Package getPackage();

    default boolean isDocumentReferenceVariable() {
        return this instanceof DocumentReferenceTranslator;
    }
}
