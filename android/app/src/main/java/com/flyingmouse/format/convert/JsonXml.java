package com.flyingmouse.format.convert;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.util.Iterator;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 轻量 JSON ↔ XML 互转。
 * Android 内置 org.json 不含 XML 类，桌面版依赖的 org.json.XML 在移动端子集不可用，
 * 此处用 DOM + org.json 自行实现，产出与桌面版同构（元素名即键、数组重复同名元素）。
 */
public final class JsonXml {

    private JsonXml() {
    }

    // ---------- JSON → XML ----------

    public static String toXml(String json) throws Exception {
        String t = json.trim();
        if (t.startsWith("[")) {
            StringBuilder sb = new StringBuilder();
            sb.append("<root>");
            arrayToXml("item", new JSONArray(t), sb, 1);
            sb.append("</root>");
            return sb.toString();
        }
        JSONObject obj = new JSONObject(t);
        StringBuilder sb = new StringBuilder();
        objToXml("root", obj, sb, 0);
        return sb.toString();
    }

    private static void objToXml(String name, JSONObject obj, StringBuilder sb, int depth) {
        if (obj.length() == 0) {
            indent(sb, depth);
            sb.append('<').append(name).append("/>\n");
            return;
        }
        indent(sb, depth);
        sb.append('<').append(name).append(">\n");
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = obj.opt(key);
            valueToXml(key, v, sb, depth + 1);
        }
        indent(sb, depth);
        sb.append("</").append(name).append(">\n");
    }

    private static void arrayToXml(String name, JSONArray arr, StringBuilder sb, int depth) {
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                if (o.length() == 0) {
                    indent(sb, depth);
                    sb.append('<').append(name).append("/>\n");
                } else {
                    objToXml(name, o, sb, depth);
                }
            } else if (v instanceof JSONArray) {
                arrayToXml(name, (JSONArray) v, sb, depth);
            } else {
                indent(sb, depth);
                sb.append('<').append(name).append('>')
                        .append(escapeXml(v == null ? "" : v.toString()))
                        .append("</").append(name).append(">\n");
            }
        }
    }

    private static void valueToXml(String key, Object v, StringBuilder sb, int depth) {
        if (v instanceof JSONObject) {
            objToXml(key, (JSONObject) v, sb, depth);
        } else if (v instanceof JSONArray) {
            arrayToXml(key, (JSONArray) v, sb, depth);
        } else if (v == null || JSONObject.NULL.equals(v)) {
            indent(sb, depth);
            sb.append('<').append(key).append("/>\n");
        } else {
            indent(sb, depth);
            sb.append('<').append(key).append('>')
                    .append(escapeXml(v.toString()))
                    .append("</").append(key).append(">\n");
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- XML → JSON ----------

    public static String fromXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new org.xml.sax.InputSource(new StringReader(xml)));
        Element root = doc.getDocumentElement();
        JSONObject out = new JSONObject();
        elementToJson(root, out, true);
        return out.toString(2);
    }

    private static void elementToJson(Element el, JSONObject target, boolean asRoot) throws Exception {
        NodeList children = el.getChildNodes();
        // 分组同名子元素
        java.util.Map<String, java.util.List<Element>> grouped = new java.util.LinkedHashMap<>();
        StringBuilder directText = new StringBuilder();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element c = (Element) n;
                hasElementChildren = true;
                grouped.computeIfAbsent(c.getNodeName(), k -> new java.util.ArrayList<>()).add(c);
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                directText.append(n.getNodeValue());
            }
        }

        String text = directText.toString().trim();
        if (!hasElementChildren) {
            target.put(asRoot ? el.getNodeName() : "value", text);
            return;
        }
        if (!text.isEmpty()) {
            target.put(asRoot ? el.getNodeName() : "#text", text);
        }
        for (java.util.Map.Entry<String, java.util.List<Element>> e : grouped.entrySet()) {
            String tag = e.getKey();
            java.util.List<Element> list = e.getValue();
            if (list.size() == 1) {
                Element child = list.get(0);
                if (child.getChildNodes().getLength() == 0) {
                    // 空元素 → null
                    target.put(tag, JSONObject.NULL);
                } else {
                    NodeList cc = child.getChildNodes();
                    boolean onlyText = true;
                    for (int i = 0; i < cc.getLength(); i++) {
                        if (cc.item(i).getNodeType() == Node.ELEMENT_NODE) {
                            onlyText = false;
                            break;
                        }
                    }
                    if (onlyText) {
                        String t = textOf(child).trim();
                        target.put(tag, t);
                    } else {
                        JSONObject sub = new JSONObject();
                        elementToJson(child, sub, false);
                        // 单元素对象拍平为 tag 直接对象
                        if (sub.length() == 1 && sub.has("#text")) {
                            target.put(tag, sub.opt("#text"));
                        } else {
                            target.put(tag, sub);
                        }
                    }
                }
            } else {
                JSONArray arr = new JSONArray();
                for (Element child : list) {
                    NodeList cc = child.getChildNodes();
                    boolean onlyText = true;
                    for (int i = 0; i < cc.getLength(); i++) {
                        if (cc.item(i).getNodeType() == Node.ELEMENT_NODE) {
                            onlyText = false;
                            break;
                        }
                    }
                    if (onlyText) {
                        arr.put(textOf(child).trim());
                    } else {
                        JSONObject sub = new JSONObject();
                        elementToJson(child, sub, false);
                        arr.put(sub);
                    }
                }
                target.put(tag, arr);
            }
        }
    }

    private static String textOf(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }
}
