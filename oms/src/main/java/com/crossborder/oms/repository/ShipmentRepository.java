package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderByRoundNo(Long orderId);

    /**
     * 회차의 주문 id (엔티티를 영속성 컨텍스트에 올리지 않는다 — 주문 행 잠금 전에 읽고, 회차 엔티티는 잠금 후 처음 읽는다)
     */
    @Query("select s.orderId from Shipment s where s.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);
}
