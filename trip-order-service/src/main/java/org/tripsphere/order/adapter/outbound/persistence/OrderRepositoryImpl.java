package org.tripsphere.order.adapter.outbound.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.tripsphere.order.adapter.outbound.persistence.entity.OrderEntity;
import org.tripsphere.order.adapter.outbound.persistence.entity.OrderItemEntity;
import org.tripsphere.order.adapter.outbound.persistence.mapper.OrderEntityMapper;
import org.tripsphere.order.application.dto.ListOrdersQuery;
import org.tripsphere.order.application.dto.OrderPage;
import org.tripsphere.order.domain.model.Order;
import org.tripsphere.order.domain.repository.OrderRepository;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderItemJpaRepository orderItemJpaRepository;
    private final OrderEntityMapper mapper;

    @Override
    public Order save(Order order) {
        OrderEntity entity = mapper.toEntity(order);
        entity = orderJpaRepository.save(entity);

        List<OrderItemEntity> itemEntities = mapper.toItemEntities(order.getItems());
        if (!itemEntities.isEmpty()) {
            orderItemJpaRepository.saveAll(itemEntities);
        }

        List<OrderItemEntity> savedItems = orderItemJpaRepository.findByOrderId(order.getId());
        return mapper.toDomain(entity, savedItems);
    }

    @Override
    public Optional<Order> findById(String id) {
        return orderJpaRepository.findById(id).map(entity -> {
            List<OrderItemEntity> items = orderItemJpaRepository.findByOrderId(id);
            return mapper.toDomain(entity, items);
        });
    }

    @Override
    public OrderPage findByUserIdWithFilters(ListOrdersQuery query) {
        int pageSize = query.pageSize() <= 0 ? 20 : Math.min(query.pageSize(), 100);
        PageRequest pageable = PageRequest.of(query.page(), pageSize);

        String statusStr = query.status() != null ? query.status().name() : null;
        String typeStr = query.type() != null ? query.type().name() : null;

        Page<OrderEntity> entityPage;
        if (statusStr != null && typeStr != null) {
            entityPage = orderJpaRepository.findByUserIdAndStatusAndTypeOrderByCreatedAtDesc(
                    query.userId(), statusStr, typeStr, pageable);
        } else if (statusStr != null) {
            entityPage =
                    orderJpaRepository.findByUserIdAndStatusOrderByCreatedAtDesc(query.userId(), statusStr, pageable);
        } else if (typeStr != null) {
            entityPage = orderJpaRepository.findByUserIdAndTypeOrderByCreatedAtDesc(query.userId(), typeStr, pageable);
        } else {
            entityPage = orderJpaRepository.findByUserIdOrderByCreatedAtDesc(query.userId(), pageable);
        }

        List<Order> orders = entityPage
                .map(entity -> {
                    List<OrderItemEntity> items = orderItemJpaRepository.findByOrderId(entity.getId());
                    return mapper.toDomain(entity, items);
                })
                .getContent();

        return new OrderPage(orders, entityPage.getTotalPages(), entityPage.hasNext(), entityPage.getNumber());
    }

    @Override
    public long getNextOrderSequence() {
        return orderJpaRepository.getNextOrderSequence();
    }

    @Override
    public void createSequenceIfNotExists() {
        orderJpaRepository.createOrderSequenceIfNotExists();
    }
}
