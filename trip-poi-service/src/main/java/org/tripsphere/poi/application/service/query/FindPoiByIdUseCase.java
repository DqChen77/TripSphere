package org.tripsphere.poi.application.service.query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tripsphere.poi.application.exception.InvalidArgumentException;
import org.tripsphere.poi.application.exception.NotFoundException;
import org.tripsphere.poi.domain.model.Poi;
import org.tripsphere.poi.domain.repository.PoiRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class FindPoiByIdUseCase {

    private final PoiRepository poiRepository;

    public Poi execute(String id) {
        if (id == null || id.isBlank()) {
            throw InvalidArgumentException.required("id");
        }
        log.debug("Finding POI by id: {}", id);
        return poiRepository.findById(id).orElseThrow(() -> new NotFoundException("POI", id));
    }
}
