package com.weatherplatform.analyticsservice.repository;

import com.weatherplatform.analyticsservice.entity.DeviceLocationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceLocationSnapshotRepository extends JpaRepository<DeviceLocationSnapshot, Long> {

    Optional<DeviceLocationSnapshot> findByDeviceUid(String deviceUid);

    /**
     * Snapshots inside a latitude/longitude box.
     *
     * A radius query used to load every snapshot in the table and then discard
     * most of them with a Haversine distance computed in Java. Haversine cannot
     * be indexed, but a box can: this narrows the candidates using
     * idx_location_snapshots_coords, and the exact distance check then runs
     * over the survivors instead of the whole fleet.
     *
     * The box is the enclosing square of the circle, so it admits corners that
     * fall outside the radius -- the caller still applies the exact test. It
     * never excludes a point that belongs.
     */
    List<DeviceLocationSnapshot> findByLatitudeBetweenAndLongitudeBetween(
            double minLatitude, double maxLatitude, double minLongitude, double maxLongitude);
}
