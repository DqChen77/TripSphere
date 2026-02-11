package org.tripsphere.itinerary.service.impl;

import com.github.f4b6a3.uuid.UuidCreator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tripsphere.itinerary.exception.InvalidArgumentException;
import org.tripsphere.itinerary.exception.NotFoundException;
import org.tripsphere.itinerary.mapper.ItineraryMapper;
import org.tripsphere.itinerary.model.ActivityDoc;
import org.tripsphere.itinerary.model.DayPlanDoc;
import org.tripsphere.itinerary.model.ItineraryDoc;
import org.tripsphere.itinerary.repository.ItineraryRepository;
import org.tripsphere.itinerary.service.ItineraryService;
import org.tripsphere.itinerary.v1.Activity;
import org.tripsphere.itinerary.v1.DayPlan;
import org.tripsphere.itinerary.v1.Itinerary;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryServiceImpl implements ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final ItineraryMapper mapper = ItineraryMapper.INSTANCE;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Override
    public Itinerary createItinerary(Itinerary itinerary) {
        log.debug("Creating new itinerary: {}", itinerary.getTitle());

        ItineraryDoc doc = mapper.toDoc(itinerary);
        // Server generates the ID, ignore any client-provided ID
        doc.setId(null);

        // Ensure all day plans and activities have IDs
        if (doc.getDayPlans() != null) {
            for (DayPlanDoc dayPlan : doc.getDayPlans()) {
                ensureDayPlanId(dayPlan);
            }
        }

        ItineraryDoc saved = itineraryRepository.save(doc);
        log.info("Created itinerary with id: {}", saved.getId());

        return mapper.toProto(saved);
    }

    @Override
    public Itinerary getItinerary(String id) {
        log.debug("Getting itinerary by id: {}", id);

        ItineraryDoc doc =
                itineraryRepository
                        .findById(id)
                        .filter(d -> !d.isArchived())
                        .orElseThrow(() -> new NotFoundException("Itinerary", id));

        return mapper.toProto(doc);
    }

    @Override
    public void archiveItinerary(String id) {
        log.debug("Archiving itinerary: {}", id);

        ItineraryDoc doc =
                itineraryRepository
                        .findById(id)
                        .orElseThrow(() -> new NotFoundException("Itinerary", id));

        doc.setArchived(true);
        itineraryRepository.save(doc);

        log.info("Archived itinerary: {}", id);
    }

    @Override
    public PageResult<Itinerary> listUserItineraries(
            String userId, int pageSize, String pageToken) {
        log.debug("Listing itineraries for user: {}, pageSize: {}", userId, pageSize);

        // Normalize page size
        int normalizedPageSize = normalizePageSize(pageSize);

        // Decode page token to get offset
        int offset = decodePageToken(pageToken);

        // Fetch one extra to determine if there are more results
        List<ItineraryDoc> docs =
                itineraryRepository.findByUserIdAndArchivedOrderByStartDateDesc(
                        userId, false, PageRequest.of(0, normalizedPageSize + 1).withPage(offset));

        boolean hasMore = docs.size() > normalizedPageSize;
        if (hasMore) {
            docs = docs.subList(0, normalizedPageSize);
        }

        List<Itinerary> itineraries = mapper.toProtoList(docs);

        String nextPageToken = hasMore ? encodePageToken(offset + 1) : null;

        return new PageResult<>(itineraries, nextPageToken);
    }

    @Override
    public DayPlan addDayPlan(String itineraryId, DayPlan dayPlan) {
        log.debug("Adding day plan to itinerary: {}", itineraryId);

        ItineraryDoc doc = getItineraryDoc(itineraryId);

        DayPlanDoc dayPlanDoc = mapper.toDayPlanDoc(dayPlan);
        ensureDayPlanId(dayPlanDoc);

        if (doc.getDayPlans() == null) {
            doc.setDayPlans(new ArrayList<>());
        }
        doc.getDayPlans().add(dayPlanDoc);

        itineraryRepository.save(doc);
        log.info("Added day plan {} to itinerary {}", dayPlanDoc.getId(), itineraryId);

        return mapper.toDayPlanProto(dayPlanDoc);
    }

    @Override
    public void deleteDayPlan(String itineraryId, String dayPlanId) {
        log.debug("Deleting day plan {} from itinerary {}", dayPlanId, itineraryId);

        ItineraryDoc doc = getItineraryDoc(itineraryId);

        boolean removed =
                doc.getDayPlans() != null
                        && doc.getDayPlans().removeIf(dp -> dp.getId().equals(dayPlanId));

        if (!removed) {
            throw new NotFoundException("DayPlan", dayPlanId);
        }

        itineraryRepository.save(doc);
        log.info("Deleted day plan {} from itinerary {}", dayPlanId, itineraryId);
    }

    @Override
    public Activity addActivity(
            String itineraryId, String dayPlanId, Activity activity, int insertIndex) {
        log.debug(
                "Adding activity to day plan {} in itinerary {} at index {}",
                dayPlanId,
                itineraryId,
                insertIndex);

        ItineraryDoc doc = getItineraryDoc(itineraryId);
        DayPlanDoc dayPlanDoc = findDayPlan(doc, dayPlanId);

        ActivityDoc activityDoc = mapper.toActivityDoc(activity);
        ensureActivityId(activityDoc);

        if (dayPlanDoc.getActivities() == null) {
            dayPlanDoc.setActivities(new ArrayList<>());
        }

        List<ActivityDoc> activities = dayPlanDoc.getActivities();
        if (insertIndex < 0 || insertIndex >= activities.size()) {
            activities.add(activityDoc);
        } else {
            activities.add(insertIndex, activityDoc);
        }

        itineraryRepository.save(doc);
        log.info(
                "Added activity {} to day plan {} in itinerary {}",
                activityDoc.getId(),
                dayPlanId,
                itineraryId);

        return mapper.toActivityProto(activityDoc);
    }

    @Override
    public Activity updateActivity(String itineraryId, String dayPlanId, Activity activity) {
        log.debug(
                "Updating activity {} in day plan {} in itinerary {}",
                activity.getId(),
                dayPlanId,
                itineraryId);

        if (activity.getId().isEmpty()) {
            throw InvalidArgumentException.required("activity.id");
        }

        ItineraryDoc doc = getItineraryDoc(itineraryId);
        DayPlanDoc dayPlanDoc = findDayPlan(doc, dayPlanId);

        List<ActivityDoc> activities = dayPlanDoc.getActivities();
        if (activities == null || activities.isEmpty()) {
            throw new NotFoundException("Activity", activity.getId());
        }

        int index = -1;
        for (int i = 0; i < activities.size(); i++) {
            if (activities.get(i).getId().equals(activity.getId())) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            throw new NotFoundException("Activity", activity.getId());
        }

        ActivityDoc updatedDoc = mapper.toActivityDoc(activity);
        activities.set(index, updatedDoc);

        itineraryRepository.save(doc);
        log.info(
                "Updated activity {} in day plan {} in itinerary {}",
                activity.getId(),
                dayPlanId,
                itineraryId);

        return mapper.toActivityProto(updatedDoc);
    }

    @Override
    public void deleteActivity(String itineraryId, String dayPlanId, String activityId) {
        log.debug(
                "Deleting activity {} from day plan {} in itinerary {}",
                activityId,
                dayPlanId,
                itineraryId);

        ItineraryDoc doc = getItineraryDoc(itineraryId);
        DayPlanDoc dayPlanDoc = findDayPlan(doc, dayPlanId);

        boolean removed =
                dayPlanDoc.getActivities() != null
                        && dayPlanDoc.getActivities().removeIf(a -> a.getId().equals(activityId));

        if (!removed) {
            throw new NotFoundException("Activity", activityId);
        }

        itineraryRepository.save(doc);
        log.info(
                "Deleted activity {} from day plan {} in itinerary {}",
                activityId,
                dayPlanId,
                itineraryId);
    }

    // ==================== Helper Methods ====================

    private ItineraryDoc getItineraryDoc(String itineraryId) {
        return itineraryRepository
                .findById(itineraryId)
                .filter(d -> !d.isArchived())
                .orElseThrow(() -> new NotFoundException("Itinerary", itineraryId));
    }

    private DayPlanDoc findDayPlan(ItineraryDoc doc, String dayPlanId) {
        if (doc.getDayPlans() == null) {
            throw new NotFoundException("DayPlan", dayPlanId);
        }
        return doc.getDayPlans().stream()
                .filter(dp -> dp.getId().equals(dayPlanId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("DayPlan", dayPlanId));
    }

    private void ensureDayPlanId(DayPlanDoc dayPlan) {
        if (dayPlan.getId() == null || dayPlan.getId().isEmpty()) {
            dayPlan.setId(UuidCreator.getTimeOrderedEpoch().toString());
        }
        if (dayPlan.getActivities() != null) {
            for (ActivityDoc activity : dayPlan.getActivities()) {
                ensureActivityId(activity);
            }
        }
    }

    private void ensureActivityId(ActivityDoc activity) {
        if (activity.getId() == null || activity.getId().isEmpty()) {
            activity.setId(UuidCreator.getTimeOrderedEpoch().toString());
        }
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String encodePageToken(int pageNumber) {
        return Base64.getEncoder()
                .encodeToString(String.valueOf(pageNumber).getBytes(StandardCharsets.UTF_8));
    }

    private int decodePageToken(String pageToken) {
        if (pageToken == null || pageToken.isEmpty()) {
            return 0;
        }
        try {
            String decoded =
                    new String(Base64.getDecoder().decode(pageToken), StandardCharsets.UTF_8);
            return Integer.parseInt(decoded);
        } catch (Exception e) {
            log.warn("Invalid page token: {}", pageToken);
            return 0;
        }
    }
}
