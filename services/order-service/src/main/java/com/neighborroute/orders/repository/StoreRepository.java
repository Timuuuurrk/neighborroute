package com.neighborroute.orders.repository;

import com.neighborroute.orders.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

}
