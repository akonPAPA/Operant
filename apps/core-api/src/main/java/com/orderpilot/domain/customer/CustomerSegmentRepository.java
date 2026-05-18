package com.orderpilot.domain.customer;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSegmentRepository extends JpaRepository<CustomerSegment, UUID> {
}