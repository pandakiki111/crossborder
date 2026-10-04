package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.ShipmentItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, Long> {

    List<ShipmentItem> findByOrderItemIdIn(Collection<Long> orderItemIds);

    List<ShipmentItem> findByShipmentIdIn(Collection<Long> shipmentIds);

    boolean existsByShipmentId(Long shipmentId);
}
