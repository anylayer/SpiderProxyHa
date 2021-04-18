package com.virjar.spider.proxy.ha.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

/**
 * appassember的打包策略，目前会把resource下面的资源打包到jar包中，classloader在加载resource的时候，是多个url重试获取资源的。
 * 此时config目录的资源配置和jar包的默认配置可能存在冲突（confuse）<br>
 * 这个工具类优先读取config目录的配置文件，如果在config目录无法读取到再调用jar包资源
 */
@Slf4j
public class ClasspathResourceUtil {

    private static InputStream getResourceAsStreamWithSystemClassLoader(ClassLoader classLoader, String path) {
        URL resource = classLoader.getResource("");
        if (resource == null) {
            log.warn("can not load relative  resource ");
            return classLoader.getResourceAsStream(path);
        }

        File file = new File(resource.getFile(), path);
        if (file.exists() && file.isFile() && file.canRead()) {
            log.info("load config from:{}", file);
            try {
                return new FileInputStream(file);
            } catch (FileNotFoundException e) {
                //not happened
            }
        } else {
            log.warn("load config file failed from none exist file:{}", file.getAbsolutePath());
        }
        return classLoader.getResourceAsStream(path);
    }

    public static InputStream getResourceAsStream(String path) {
        ClassLoader classLoader = ClasspathResourceUtil.class.getClassLoader();
        if (!(classLoader instanceof URLClassLoader)) {
            return getResourceAsStreamWithSystemClassLoader(classLoader, path);
        }
        URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
        try {
            Enumeration<URL> resources = urlClassLoader.getResources(".");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (!url.toString().startsWith("file:")) {
                    continue;
                }
                File file = new File(url.getFile(), path);
                if (file.exists() && file.isFile() && file.canRead()) {
                    log.info("load config from:{}", file);
                    try {
                        return new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        //not happened
                    }
                }

            }
        } catch (IOException e) {
            //not happen
        }

        return getResourceAsStreamWithSystemClassLoader(classLoader, path);
    }
}
