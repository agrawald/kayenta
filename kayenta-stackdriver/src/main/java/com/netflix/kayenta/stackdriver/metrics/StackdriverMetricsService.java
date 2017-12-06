/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.kayenta.stackdriver.metrics;

import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.ListTimeSeriesResponse;
import com.google.api.services.monitoring.v3.model.Metric;
import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.api.services.monitoring.v3.model.Point;
import com.google.api.services.monitoring.v3.model.TimeSeries;
import com.netflix.kayenta.canary.CanaryMetricConfig;
import com.netflix.kayenta.canary.CanaryScope;
import com.netflix.kayenta.canary.providers.StackdriverCanaryMetricSetQueryConfig;
import com.netflix.kayenta.google.security.GoogleNamedAccountCredentials;
import com.netflix.kayenta.metrics.MetricSet;
import com.netflix.kayenta.metrics.MetricsService;
import com.netflix.kayenta.security.AccountCredentialsRepository;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Builder
@Slf4j
public class StackdriverMetricsService implements MetricsService {

  @NotNull
  @Singular
  @Getter
  private List<String> accountNames;

  @Autowired
  private final AccountCredentialsRepository accountCredentialsRepository;

  @Autowired
  private final Registry registry;

  @Override
  public String getType() {
    return "stackdriver";
  }

  @Override
  public boolean servicesAccount(String accountName) {
    return accountNames.contains(accountName);
  }

  @Override
  public List<MetricSet> queryMetrics(String metricsAccountName,
                                      CanaryMetricConfig canaryMetricConfig,
                                      CanaryScope canaryScope) throws IOException {
    GoogleNamedAccountCredentials stackdriverCredentials = (GoogleNamedAccountCredentials)accountCredentialsRepository
      .getOne(metricsAccountName)
      .orElseThrow(() -> new IllegalArgumentException("Unable to resolve account " + metricsAccountName + "."));
    Monitoring monitoring = stackdriverCredentials.getMonitoring();
    StackdriverCanaryMetricSetQueryConfig stackdriverMetricSetQuery = (StackdriverCanaryMetricSetQueryConfig)canaryMetricConfig.getQuery();
    // TODO(duftler): Make this filter general-purpose so it works for more than just GCE.
    // TODO(duftler): The 'project' attribute should be part of the scope (a StackdriverCanaryScope), and not just re-used from stackdriverCredentials.
    String projectId = stackdriverCredentials.getProject();
    String region = canaryScope.getRegion();
    String filter = "metric.type=\"" + stackdriverMetricSetQuery.getMetricType() + "\"" +
                    " AND resource.labels.project_id=" + projectId +
                    " AND resource.metadata.tag.spinnaker-region=" + region +
                    " AND resource.metadata.tag.spinnaker-server-group=" + canaryScope.getScope() +
                    " AND resource.type = gce_instance";
    long alignmentPeriodSec = canaryScope.getStep();
    Monitoring.Projects.TimeSeries.List list = monitoring
      .projects()
      .timeSeries()
      .list("projects/" + stackdriverCredentials.getProject())
      .setAggregationAlignmentPeriod(alignmentPeriodSec + "s")
      .setAggregationCrossSeriesReducer("REDUCE_MEAN")
      .setAggregationPerSeriesAligner("ALIGN_MEAN")
      // TODO(duftler): Support 'filter' directly on StackdriverCanaryMetricSetQueryConfig?
      .setFilter(filter)
      .setIntervalStartTime(canaryScope.getStart().toString())
      .setIntervalEndTime(canaryScope.getEnd().toString());

    List<String> groupByFields = stackdriverMetricSetQuery.getGroupByFields();

    if (groupByFields != null) {
      list.setAggregationGroupByFields(groupByFields);
    }

    long startTime = registry.clock().monotonicTime();
    ListTimeSeriesResponse response;

    try {
      response = list.execute();
    } finally {
      long endTime = registry.clock().monotonicTime();
      Id stackdriverFetchTimerId = registry.createId("stackdriver.fetchTime").withTag("project", projectId).withTag("region", region);

      registry.timer(stackdriverFetchTimerId).record(endTime - startTime, TimeUnit.NANOSECONDS);
    }

    long startAsLong = canaryScope.getStart().toEpochMilli();
    long endAsLong = canaryScope.getEnd().toEpochMilli();
    long elapsedSeconds = (endAsLong - startAsLong) / 1000;
    long numIntervals = elapsedSeconds / alignmentPeriodSec;
    long remainder = elapsedSeconds % alignmentPeriodSec;

    if (remainder > 0) {
      numIntervals++;
    }

    List<TimeSeries> timeSeriesList = response.getTimeSeries();

    if (timeSeriesList == null || timeSeriesList.size() == 0) {
      // Add placeholder metric set.
      timeSeriesList = Collections.singletonList(new TimeSeries().setMetric(new Metric()).setPoints(new ArrayList<>()));
    }

    List<MetricSet> metricSetList = new ArrayList<>();

    for (TimeSeries timeSeries : timeSeriesList) {
      List<Point> points = timeSeries.getPoints();

      if (points.size() != numIntervals) {
        String pointOrPoints = numIntervals == 1 ? "point" : "points";

        log.warn("Expected {} data {}, but received {}.", numIntervals, pointOrPoints, points.size());
      }

      Collections.reverse(points);

      Instant responseStartTimeInstant =
        points.size() > 0
        ? Instant.parse(points.get(0).getInterval().getStartTime())
        : canaryScope.getStart();
      long responseStartTimeMillis = responseStartTimeInstant.toEpochMilli();

      // TODO(duftler): What if there are no data points?
      List<Double> pointValues =
        points
          .stream()
          .map(point -> point.getValue().getDoubleValue())
          .collect(Collectors.toList());

      MetricSet.MetricSetBuilder metricSetBuilder =
        MetricSet.builder()
          .name(canaryMetricConfig.getName())
          .startTimeMillis(responseStartTimeMillis)
          .startTimeIso(responseStartTimeInstant.toString())
          .stepMillis(alignmentPeriodSec * 1000)
          .values(pointValues);

      MonitoredResource monitoredResource = timeSeries.getResource();

      if (monitoredResource != null) {
        Map<String, String> labels = monitoredResource.getLabels();

        if (labels != null) {
          metricSetBuilder.tags(labels);
        }
      }

      metricSetList.add(metricSetBuilder.build());
    }

    return metricSetList;
  }
}
