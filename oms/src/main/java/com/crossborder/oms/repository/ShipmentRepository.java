package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
}
