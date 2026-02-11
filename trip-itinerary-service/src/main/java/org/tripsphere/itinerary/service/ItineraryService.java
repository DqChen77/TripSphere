package org.tripsphere.itinerary.service;

import java.util.List;
import org.tripsphere.itinerary.v1.Activity;
import org.tripsphere.itinerary.v1.DayPlan;
import org.tripsphere.itinerary.v1.Itinerary;

/** Service interface for managing itineraries. */
public interface ItineraryService {

    /**
     * Create a new itinerary.
     *
     * @param itinerary the itinerary to create (ID will be generated if not provided)
     * @return the created itinerary with generated ID
     */
    Itinerary createItinerary(Itinerary itinerary);

    /**
     * Get an itinerary by ID.
     *
     * @param id the itinerary ID
     * @return the itinerary
     * @throws org.tripsphere.itinerary.exception.NotFoundException if not found
     */
    Itinerary getItinerary(String id);

    /**
     * Archive (soft delete) an itinerary.
     *
     * @param id the itinerary ID
     * @throws org.tripsphere.itinerary.exception.NotFoundException if not found
     */
    void archiveItinerary(String id);

    /**
     * List itineraries for a user with pagination.
     *
     * @param userId the user ID
     * @param pageSize the maximum number of results to return
     * @param pageToken the page token for pagination (null for first page)
     * @return a page result containing itineraries and next page token
     */
    PageResult<Itinerary> listUserItineraries(String userId, int pageSize, String pageToken);

    /**
     * Add a day plan to an itinerary.
     *
     * @param itineraryId the itinerary ID
     * @param dayPlan the day plan to add
     * @return the added day plan with generated ID
     * @throws org.tripsphere.itinerary.exception.NotFoundException if itinerary not found
     */
    DayPlan addDayPlan(String itineraryId, DayPlan dayPlan);

    /**
     * Delete a day plan from an itinerary.
     *
     * @param itineraryId the itinerary ID
     * @param dayPlanId the day plan ID
     * @throws org.tripsphere.itinerary.exception.NotFoundException if itinerary or day plan not
     *     found
     */
    void deleteDayPlan(String itineraryId, String dayPlanId);

    /**
     * Add an activity to a day plan.
     *
     * @param itineraryId the itinerary ID
     * @param dayPlanId the day plan ID
     * @param activity the activity to add
     * @param insertIndex the index to insert at (appends if out of range)
     * @return the added activity with generated ID
     * @throws org.tripsphere.itinerary.exception.NotFoundException if itinerary or day plan not
     *     found
     */
    Activity addActivity(String itineraryId, String dayPlanId, Activity activity, int insertIndex);

    /**
     * Update an activity in a day plan.
     *
     * @param itineraryId the itinerary ID
     * @param dayPlanId the day plan ID
     * @param activity the updated activity (must have valid ID)
     * @return the updated activity
     * @throws org.tripsphere.itinerary.exception.NotFoundException if any resource not found
     */
    Activity updateActivity(String itineraryId, String dayPlanId, Activity activity);

    /**
     * Delete an activity from a day plan.
     *
     * @param itineraryId the itinerary ID
     * @param dayPlanId the day plan ID
     * @param activityId the activity ID
     * @throws org.tripsphere.itinerary.exception.NotFoundException if any resource not found
     */
    void deleteActivity(String itineraryId, String dayPlanId, String activityId);

    /** Page result wrapper for pagination. */
    record PageResult<T>(List<T> items, String nextPageToken) {}
}
