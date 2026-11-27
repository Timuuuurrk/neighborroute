package com.neighborroute.orders.scheduler;


import com.neighborroute.orders.domain.Order;
import com.neighborroute.orders.domain.OrderStatus;
import com.neighborroute.orders.repository.OrderRepository;
import com.neighborroute.orders.service.OrderCancellationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "neighborroute.cancellation.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrderCancellationRecoveryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    OrderCancellationRecoveryScheduler.class
            );

    private final OrderRepository orderRepository;
    private final OrderCancellationCoordinator coordinator;

    public OrderCancellationRecoveryScheduler(
            OrderRepository orderRepository,
            OrderCancellationCoordinator coordinator
    ) {
        this.orderRepository = orderRepository;
        this.coordinator = coordinator;
    }

    @Scheduled(
            fixedDelayString =
                    "${neighborroute.cancellation.recovery.delay-ms:30000}",
            initialDelayString =
                    "${neighborroute.cancellation.recovery.initial-delay-ms:30000}"
    )
    public void recoverPendingCancellations() {
        List<Order> pendingOrders =
                orderRepository.findTop50ByStatusOrderByCreatedAtAsc(
                        OrderStatus.CANCEL_PENDING
                );

        for (Order order : pendingOrders) {
            try {
                coordinator.cancel(order.getId());

            } catch (Exception ex) {
                log.warn(
                        "Failed to recover cancellation for order {}",
                        order.getId(),
                        ex
                );
            }
        }
    }
}
