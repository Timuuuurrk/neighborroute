package com.neighborroute.orders.metrics;

import com.neighborroute.orders.domain.OrderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class BusinessMetrics {
    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry registry;
    private final List<Metric> metrics = new ArrayList<>();

    public BusinessMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        for (OrderStatus status : OrderStatus.values()) {
            track("neighborroute.orders", "status", status.name(),
                    "SELECT count(*) FROM orders WHERE status = ?", status.name());
        }
        track("neighborroute.outbox.pending", null, null,
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL");
    }

    private void track(String name, String tag, String tagValue, String sql, Object... args) {
        AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
        Gauge.Builder<AtomicReference<Double>> builder = Gauge.builder(name, value, v -> v.get());
        if (tag != null) {
            builder.tag(tag, tagValue);
        }
        builder.register(registry);
        metrics.add(new Metric(sql, args, value));
    }

    @Scheduled(fixedDelay = 30000)
    public void refresh() {
        for (Metric metric : metrics) {
            try {
                Long count = jdbcTemplate.queryForObject(metric.sql(), Long.class, metric.args());
                metric.value().set(count == null ? Double.NaN : count.doubleValue());
            } catch (DataAccessException e) {
                metric.value().set(Double.NaN);
                log.warn("Failed to refresh business metric: {}", metric.sql(), e);
            }
        }
    }

    private record Metric(String sql, Object[] args, AtomicReference<Double> value) {}
}
