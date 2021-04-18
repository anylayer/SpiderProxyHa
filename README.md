# SpiderProxyHa

这是一个面向爬虫（抓取）业务的代理IP中间件，使用他可以让你采购的代理ip不会出现离线或者不可用的情况。




## 使用

### 依赖
基于netty，java生态。要求jdk1.8

### 系统配置要求

要求不高，1核2G的Linux服务器即可

### 构建

mac/linux
```
./mvnw -Pprod  clean -Dmaven.test.skip=true package appassembler:assemble
```
windows
```
mvnw.cmd -Pprod  clean -Dmaven.test.skip=true package appassembler:assemble
```

之后得到文件夹:``target/dist-spider-proxy-ha-1.0`` 即为可执行文件


### 配置

本项目不是代理ip产生服务器，而是对代理ip资源进行高可用的中间层服务。也即他是一个代理ip的代理服务器。
使用当前服务之前，需要配置你采购的代理ip。SpiderProxyHa的主要配置文件在``conf/config.ini`` 配置项如下：
```
[global]
type = global
refreshUpstreamInterval = 30
# 连接池中缓存的连接数量
cache_connection_size = 3
# 连接池中缓存的连接时间，太久可能会僵死
cache_connection_seconds = 30

# 唯一的名称，可以配置多个采购的代理ip源，或者为业务配置独立的ip源。保证各业务ip使用的资源独立
[source_dly_virjar]
# 配置类型，只能是source
type = source
# 一个代理源的描述
name = 代理云virjar代理源
# 对应的代理支持的协议，目前SpiderProxyHa只支持 http/https/socks4/socks5
# 同时SpiderProxyHa只会做同协议转发，而不会做http over socks(至少开源版本不会做)
protocol = http/https/socks4/socks5

# 非常重要，为你加载ip的数据源，需要是ip:port\nip:port结构，如果不是那么你需要自己实现一个中间服务进行格式转换
# 这里的代理比如代理云或者其他类似的代理ip厂商
source_url = http://dailiyun.v4.dailiyun.com/query.txt?key=WQ5D646245&word=&count=200&rand=true&detail=false
# 本地服务组，SpiderProxyHa会在mapping_space对应的端口上开启代理服务，最终的代理请求则是转发到上游代理资源上
# 需要注意，这里的端口范围不能太大，正常应为source_url返回结果数量的 65%，因为上游加载的ip数量可能有一部分无法使用。另外上游ip出问题的时候，需要留下一定buffer实现软切
mapping_space = 36000-36149

## 注意下，如果你的上游代理ip是通过ip白名单鉴权，或者不需要鉴权，那么这里的密码不需要配置
# 上游代理服务器的账户,你的代理供应商会提供给你
upstream_auth_user = your_proxy_use
# 上游代理服务器的密码,你的代理供应商会提供给你
upstream_auth_password = your_proxy_password

```

### 启动
配置好之后，启动脚本 ``bin/SpiderProxyHa.bat``或者``bin/SpiderProxyHa.sh``

### 业务使用

直接将你业务的代理服务器配置到运行SpiderProxyHa的服务器，端口为 mapping_space配置的端口。

每个端口都会对应一个特定的上游代理ip资源，正常情况下，除非检测到上游ip掉线。否则不会修改mapping关系

## 二开和Pro功能
本项目开源免费，但是他只是一个基础，还有很多牛逼功能可以做。需要的就主动来找我了(微信 virjar1)

1. 业务API和数据库，可以通过http调用手动修改mapping关系(可以理解为完成ip重播，并且效果是实现秒拨)
2. MITM，中间人攻击。这是核心功能。可以在SpiderProxyHa层面修改http请求和返回。实现抓取数据返回拦截，js/图片资源注入。甚至可以解决当前SekiroJs跨域问题
3. 鉴权，目前在SpiderProxyHa层面，没有实现代理的权限模块。


