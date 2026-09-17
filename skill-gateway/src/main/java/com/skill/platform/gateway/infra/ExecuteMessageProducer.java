package com.skill.platform.gateway.infra;

/**
 * 执行消息生产端口：受理成功后将 taskId 投递到 skill-execute（worker scheduler 消费）。
 */
public interface ExecuteMessageProducer {

    void sendExecute(String taskId);
}
