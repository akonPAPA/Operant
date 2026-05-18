package com.orderpilot.domain.imports;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {
}