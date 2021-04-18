package com.virjar.spider.proxy.ha;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public interface Constants {
    String CONFIG_FILE = "config.ini";

    AttributeKey<Channel> NEXT_CHANNEL = AttributeKey.newInstance("next_channel");

    interface CONFIG_SECTION {
        String CONFIG_SECTION_TYPE_SOURCE = "source";
        String TYPE = "type";
        String NAME = "name";
        String PROTOCOL = "protocol";
        String MAPPING_SPACE = "mapping_space";
        String SOURCE_URL = "source_url";
        String UPSTREAM_AUTH_USER = "upstream_auth_user";
        String UPSTREAM_AUTH_PASSWORD = "upstream_auth_password";

    }

    interface CONFIG_GLOBAL {
        String REFRESH_UPSTREAM_INTERVAL = "refreshUpstreamInterval";
        String CONFIG_SECTION_TYPE_GLOBAL = "global";
        String CACHE_CONNECTION_SIZE = "cache_connection_size";
        String CACHE_CONNECTION_ALIVE_SECONDS = "cache_connection_seconds";
    }


}
