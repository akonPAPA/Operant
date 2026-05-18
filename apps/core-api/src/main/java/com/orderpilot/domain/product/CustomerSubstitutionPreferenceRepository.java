package com.orderpilot.domain.product;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSubstitutionPreferenceRepository extends JpaRepository<CustomerSubstitutionPreference, UUID> {
}