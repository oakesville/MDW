/*
 * Copyright (C) 2018 CenturyLink, Inc.
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
package com.centurylink.mdw.image;

import com.centurylink.mdw.model.project.Data;
import com.centurylink.mdw.model.workflow.ActivityImplementor;
import org.json.JSONObject;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;

public class Implementors extends LinkedHashMap<String,ActivityImplementor> {

    private File assetRoot;

    public Implementors(File assetRoot) {
        this.assetRoot = assetRoot;
        // load from assets (e.g. CLI) -- does not include compiled source annotations
        loadAssetImplementors(assetRoot);
    }

    /**
     * Loads implementors from assets (impl or annotated source code).
     */
    private void loadAssetImplementors(File assetDir) {
        for (File file : assetDir.listFiles()) {
            if (file.isDirectory()) {
                loadAssetImplementors(file);
            }
            else if (file.exists()) {
                try {
                    if (file.getName().endsWith("impl")) {
                        add(new ActivityImplementor(new JSONObject(new String(Files.readAllBytes(file.toPath())))));
                    }
                    else if (file.getName().endsWith(".java") || file.getName().endsWith(".kt")) {
                        ActivityAnnotationParser parser = new ActivityAnnotationParser(assetRoot);
                        ActivityImplementor impl = parser.parse(file);
                        if (impl != null)
                            add(impl);
                    }
                }
                catch (Exception e) {
                    System.out.println("Problem loading implementor " + file + ": " + e);
                }
            }
        }
    }

    public void add(ActivityImplementor impl) {
        String iconAsset = impl.getIcon();
        if (iconAsset != null && !iconAsset.startsWith("shape:")) {
            String iconPkg = Data.BASE_PKG;
            int slash = iconAsset.lastIndexOf('/');
            if (slash > 0) {
                iconPkg = iconAsset.substring(0, slash);
                iconAsset = iconAsset.substring(slash + 1);
            }
            else {
                String implClass = impl.getImplementorClass();
                String pkg = implClass.substring(0, implClass.lastIndexOf('.'));
                if (new File(assetRoot + "/" + pkg.replace('.', '/') + "/" + iconAsset).isFile())
                    iconPkg = pkg;
            }
            String iconPath = assetRoot + "/" + iconPkg.replace('.', '/') + "/" + iconAsset;
            try {
                impl.setImageIcon(getIcon(iconPath));
            }
            catch (IOException ex) {
                System.err.println("Cannot find icon " + iconPath + " for implementor " + impl.getImplementorClass());
            }
        }
        put(impl.getImplementorClass(), impl);
    }

    private ImageIcon getIcon(String iconPath) throws IOException {
        File imgFile = new File(iconPath);
        return new ImageIcon(Files.readAllBytes(Paths.get(imgFile.getPath())));
    }
}
