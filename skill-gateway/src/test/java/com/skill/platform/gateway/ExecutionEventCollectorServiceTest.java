package com.skill.platform.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
import com.skill.platform.gateway.service.ExecutionEventCollectorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行事件 collector 单元测试（TC-TR-002 写入侧）：批量 seq 连续分配、跨批续接、
 * 超限/缺参拒绝、DB 故障隔离（返回 0 不抛出）。
 */
class ExecutionEventCollectorServiceTest {

    private final ExecutionEventMapper mapper = mock(ExecutionEventMapper.class);
    private final ExecutionEventCollectorService collector =
            new ExecutionEventCollectorService(mapper, new ObjectMapper());

    private ExecutionEventCollectorService.EventInput event(String taskId, String stage) {
        return new ExecutionEventCollectorService.EventInput(taskId, "acme", stage, "OK",
                "worker", 120L, null, null, LocalDateTime.now());
    }

    @Test
    void batch_writes_three_events_with_continuous_seq() {
        when(mapper.selectOne(any())).thenReturn(null);

        int accepted = collector.collectBatch(List.of(
                event("t1", "ACCEPT"), event("t1", "AUTH"), event("t1", "FREEZE")));

        assertThat(accepted).isEqualTo(3);
        ArgumentCaptor<ExecutionEvent> captor = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(mapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ExecutionEvent::getSeq)
                .containsExactly(1, 2, 3);
        assertThat(captor.getAllValues()).extracting(ExecutionEvent::getStage)
                .containsExactly("ACCEPT", "AUTH", "FREEZE");
        assertThat(captor.getAllValues()).extracting(ExecutionEvent::getTaskId)
                .containsOnly("t1");
        assertThat(captor.getAllValues()).extracting(ExecutionEvent::getOccurredAt)
                .allMatch(v -> v != null);
    }

    @Test
    void seq_continues_across_batches_for_same_task() {
        // 用有状态 Answer 模拟 DB：insert 累积 max(seq)，selectOne 返回当前最大值
        final int[] maxSeq = {0};
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            if (maxSeq[0] == 0) {
                return null;
            }
            ExecutionEvent highest = new ExecutionEvent();
            highest.setTaskId("t1");
            highest.setSeq(maxSeq[0]);
            return highest;
        });
        when(mapper.insert(any())).thenAnswer(inv -> {
            maxSeq[0] = Math.max(maxSeq[0], ((ExecutionEvent) inv.getArgument(0)).getSeq());
            return 1;
        });

        collector.collectBatch(List.of(event("t1", "CLAIM"), event("t1", "STAGE"), event("t1", "SANDBOX")));
        int accepted = collector.collectBatch(List.of(event("t1", "SETTLE"), event("t1", "FINALIZE")));

        assertThat(accepted).isEqualTo(2);
        ArgumentCaptor<ExecutionEvent> captor = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(mapper, times(5)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ExecutionEvent::getSeq)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void over_limit_batch_is_rejected_without_writes() {
        List<ExecutionEventCollectorService.EventInput> tooMany = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> event("t1", "STAGE")).toList();

        assertThatThrownBy(() -> collector.collectBatch(tooMany))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超上限");
        verify(mapper, never()).insert(any());
    }

    @Test
    void blank_task_or_stage_is_rejected() {
        assertThatThrownBy(() -> collector.collectBatch(
                List.of(new ExecutionEventCollectorService.EventInput(" ", "acme", "STAGE",
                        "OK", "worker", null, null, null, null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> collector.collectBatch(
                List.of(new ExecutionEventCollectorService.EventInput("t1", "acme", null,
                        "OK", "worker", null, null, null, null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("stage");
        verify(mapper, never()).insert(any());
    }

    @Test
    void db_failure_returns_zero_and_does_not_throw() {
        when(mapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        int accepted = collector.collectBatch(List.of(event("t1", "STAGE")));

        assertThat(accepted).isZero();
    }
}
