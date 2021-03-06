package com.centurylink.mdw.common.translator.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;

import org.apache.commons.codec.binary.Base64;

import com.centurylink.mdw.java.CompiledJavaCache;
import com.centurylink.mdw.java.MdwJavaException;
import com.centurylink.mdw.translator.DocumentReferenceTranslator;
import com.centurylink.mdw.translator.TranslationException;

public class JavaObjectTranslator extends DocumentReferenceTranslator {

    @Override
    public Object toObject(String str, String type) throws TranslationException {
        ObjectInputStream ois = null;
        try {
            byte[] decoded = decodeBase64(str);
            ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
            ois = new ObjectInputStream(bais);
            try {
                return ois.readObject();
            }
            catch (ClassNotFoundException ex) {
                ois.close();
                bais = new ByteArrayInputStream(decoded);
                ois = new ObjectInputStream(bais) {
                    @Override
                    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                        try {
                            return CompiledJavaCache.getResourceClass(desc.getName(), getClass().getClassLoader(), getPackage());
                        }
                        catch (ClassNotFoundException ex){
                            if (getPackage()  != null && getPackage().getClassLoader() != null)
                                return getPackage().getClassLoader().loadClass(desc.getName());
                            else
                                throw ex;
                        }
                        catch (MdwJavaException ex) {
                            throw new ClassNotFoundException(desc.getName(), ex);
                        }
                    }
                };
                return ois.readObject();
            }
        }
        catch (Throwable t) {  // including NoClassDefFoundError
            throw new TranslationException(t.getMessage(), t);
        }
        finally {
            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException ex) {}
            }
        }
    }

    @Override
    public String toString(Object obj, String variableType) throws TranslationException {
        if (!(obj instanceof Serializable))
            throw new TranslationException("Object must implement java.io.Serializable: " + obj.getClass());

        ObjectOutputStream oos = null;
        try {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          oos = new ObjectOutputStream(baos);
          oos.writeObject(obj);
          return encodeBase64(baos.toByteArray());
        }
        catch (IOException ex) {
            throw new TranslationException(ex.getMessage(), ex);
        }
        finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (IOException ex) {}
            }
        }
    }

    protected byte[] decodeBase64(String inputString) {
        return Base64.decodeBase64(inputString.getBytes());
    }

    protected String encodeBase64(byte[] inputBytes) {
        return new String(Base64.encodeBase64(inputBytes));
    }
}