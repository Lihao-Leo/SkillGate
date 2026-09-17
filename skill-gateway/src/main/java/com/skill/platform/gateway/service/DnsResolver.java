package com.skill.platform.gateway.service;

import java.net.InetAddress;
import java.util.List;

/**
 * DNS 解析端口：保留地址校验用；默认系统解析，测试注入伪造解析器避免网络依赖。
 */
public interface DnsResolver {

    List<InetAddress> resolve(String host) throws java.net.UnknownHostException;
}
