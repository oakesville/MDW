package com.centurylink.mdw.util;

import com.centurylink.mdw.common.MdwException;
import com.centurylink.mdw.model.variable.VariableInstance;
import com.centurylink.mdw.model.workflow.Package;
import org.apache.commons.beanutils.PropertyUtilsBean;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExpressionUtil {

    private static PropertyUtilsBean propUtilsBean = new PropertyUtilsBean();
    private static Pattern tokenPattern = Pattern.compile("([\\$#]\\{.*?\\})");

    /**
     * Substitutes dynamic values for expressions in the input string.
     * @param input raw input string
     * @param model object containing the values to substitute
     * @return string with values substituted
     */
    public static String substitute(String input, Object model)
    throws MdwException {
        return substitute(input, model, null, false);
    }

    public static String substitute(String input, Object model, boolean lenient)
    throws MdwException {
        return substitute(input, model, null, lenient);
    }

    /**
     * Substitutes dynamic values for expressions in the input string.
     * @param input raw input string
     * @param model object containing the values to substitute
     * @param imageMap of images to populate based on special ${image:*.gif} syntax
     * @return string with values substituted
     */
    public static String substitute(String input, Object model, Map<String,String> imageMap, boolean lenient)
    throws MdwException {
        StringBuffer substituted = new StringBuffer(input.length());
        try {
            Matcher matcher = tokenPattern.matcher(input);
            int index = 0;
            while (matcher.find()) {
                String match = matcher.group();
                substituted.append(input.substring(index, matcher.start()));
                if (imageMap != null && (match.startsWith("${image:") || match.startsWith("#{image:"))) {
                    String imageFile = match.substring(8, match.length() - 1);
                    String imageId = imageFile.substring(0, imageFile.lastIndexOf('.'));
                    substituted.append("cid:" + imageId);
                    imageMap.put(imageId, imageFile);
                }
                else if (match.startsWith("#{")) { // ignore #{... in favor of facelets (except images)
                    substituted.append(match);
                }
                else {
                    Object value;
                    if (lenient) {
                        try {
                            value = propUtilsBean.getProperty(model, match.substring(2, match.length() - 1));
                            if (value == null)
                                value = match;
                        } catch (Exception e) {
                            value = match;
                        }
                    } else {
                        value = propUtilsBean.getProperty(model, match.substring(2, match.length() - 1));
                    }
                    if (value != null)
                        substituted.append(value);
                }
                index = matcher.end();
            }
            substituted.append(input.substring(index));
            return substituted.toString();
        }
        catch (Exception ex) {
            throw new MdwException("Error substituting expression value(s)", ex);
        }
    }

    /**
     * Substitutes dynamic values for expressions in the input string.
     * @param input raw input string
     * @param variables variable instances to use in substitutions
     * @return string with values substituted
     */
    public static String substitute(String input, List<VariableInstance> variables, Package pkg)
    throws MdwException {
        StringBuffer substituted = new StringBuffer(input.length());
        try {
            Matcher matcher = tokenPattern.matcher(input);
            int index = 0;
            while (matcher.find()) {
                String match = matcher.group();
                substituted.append(input.substring(index, matcher.start()));
                Object value = getVariableValue(match.substring(2, match.length() - 1), variables, pkg);
                if (value != null)
                    substituted.append(value);
                index = matcher.end();
            }
            substituted.append(input.substring(index));
            return substituted.toString();
        }
        catch (Exception ex) {
            throw new MdwException("Error substituting expression value(s) in input: '" + input + "'", ex);
        }
    }

    private static Object getVariableValue(String name, List<VariableInstance> variables, Package pkg) {
        for (int i = 0; i < variables.size(); i++) {
            if (name.equalsIgnoreCase(variables.get(i).getName())) {
                return variables.get(i).getData(pkg);
            }
        }
        return null;
    }

    /**
     * Input is email template with image tags:
     * <code>
     * &lt;img src="${image:com.centurylink.mdw.base/mdw.png}" alt="MDW"&gt;
     * </code>
     * Uses the unqualified image name as its CID.  Populates imageMap with results.
     */
    public static String substituteImages(String input, Map<String,String> imageMap) {
        StringBuffer substituted = new StringBuffer(input.length());
        Matcher matcher = tokenPattern.matcher(input);
        int index = 0;
        while (matcher.find()) {
            String match = matcher.group();
            substituted.append(input.substring(index, matcher.start()));
            if (imageMap != null && (match.startsWith("${image:"))) {
                String imageFile = match.substring(8, match.length() - 1);
                String imageId = imageFile.substring(imageFile.lastIndexOf('/') + 1);
                substituted.append("cid:" + imageId);
                imageMap.put(imageId, imageFile);
            }
            else {
                // ignore everything but images
                substituted.append(match);
            }
            index = matcher.end();
        }
        substituted.append(input.substring(index));
        return substituted.toString();
    }
}
