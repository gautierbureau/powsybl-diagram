/**
 * Copyright (c) 2025-2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.diagram.util.layout.forces;

import com.powsybl.diagram.util.layout.forces.util.NoOverlapPointSize;
import com.powsybl.diagram.util.layout.forces.util.RandomForce;
import com.powsybl.diagram.util.layout.geometry.LayoutContext;
import com.powsybl.diagram.util.layout.geometry.Point;
import com.powsybl.diagram.util.layout.geometry.Vector2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Nathan Dissoubray {@literal <nathan.dissoubray at rte-france.com>}
 */
public class RepulsionForceDegreeBasedNoOverlapLinear<V, E> implements Force<V, E> {
    private final double forceIntensityNoOverlap;
    private final double forceIntensityWithOverlap;
    private final double repulsionZoneRatio;
    private double repulsionZoneRadius;
    private final NoOverlapPointSize pointSizeRecord;
    /**
     * All the points of the layout, snapshotted at init: this force is applied point to point,
     * iterating an array is significantly faster than iterating the entry set of the points map
     */
    private Point[] allPoints = new Point[0];
    /**
     * <p>A uniform spatial hash grid over the points, used to only consider the points close enough to possibly
     * interact: this force is exactly zero beyond {@link #repulsionZoneRadius}, so points in cells further away
     * than one cell (with the cell size including a safety margin, see {@link #updateGrid()}) contribute nothing.
     * This turns the all-pairs O(n²) interaction loop into a neighborhood query, without changing the value of
     * the computed force for interacting pairs.</p>
     * <p>The grid must be refreshed with {@link #updateGrid()} each time the points have moved.</p>
     */
    private final Map<Long, List<Point>> grid = new HashMap<>();
    private double cellSize;

    /**
     * Build a repulsion force to prevent overlap of points that have a given pointSize, only consider points closer than pointSize * repulsionZoneRatio for the repulsion interaction
     * @param forceIntensityNoOverlap the intensity of the force when points are not overlapping, this should be at least an order of magnitude lower than forceIntensityWithOverlap
     * @param forceIntensityWithOverlap the intensity of the force when points are overlapping, this should be at least an order of magnitude bigger than forceIntensityNoOverlap
     * @param pointSizeScale scaling coefficient for the size of the point given the number of nodes of the graph, get the size of a point via scale * graph size + offset
     * @param pointSizeOffset offset for the size of the point, get the size of a point via scale * graph size + offset
     * @param repulsionZoneRatio the zone in which to consider the interaction with other points is a disc of radius repulsionZoneRatio * pointSize
     */
    public RepulsionForceDegreeBasedNoOverlapLinear(double forceIntensityNoOverlap, double forceIntensityWithOverlap, double pointSizeScale, double pointSizeOffset, double repulsionZoneRatio) {
        this.forceIntensityNoOverlap = forceIntensityNoOverlap;
        this.forceIntensityWithOverlap = forceIntensityWithOverlap;
        this.repulsionZoneRatio = repulsionZoneRatio;
        // using default value, will change later
        this.pointSizeRecord = new NoOverlapPointSize(pointSizeScale, pointSizeOffset);
        this.repulsionZoneRadius = pointSizeRecord.getPointSize() * repulsionZoneRatio;
    }

    @Override
    public void init(LayoutContext<V, E> layoutContext) {
        // init point size
        pointSizeRecord.calculatePointSize(layoutContext.getAllPoints().size());
        layoutContext.cacheDegree();
        this.repulsionZoneRadius = this.repulsionZoneRatio * this.pointSizeRecord.getPointSize();
        // snapshot in the map iteration order, so the interactions are enumerated deterministically
        this.allPoints = layoutContext.getAllPoints().values().toArray(new Point[0]);
        // The cell size is the interaction radius plus a safety margin: the points move between two grid updates
        // (they are updated one by one during a sweep), by at most twice the point size each (their displacement is
        // clamped), so a pair of points can get closer by at most 4 * pointSize between the grid update and the query
        this.cellSize = repulsionZoneRadius + 4 * pointSizeRecord.getPointSize();
        updateGrid();
    }

    /**
     * Rebuild the spatial hash grid from the current position of the points. Call this each time the points have
     * moved (typically once per iteration of the algorithm using this force), otherwise interactions may be missed.
     */
    public void updateGrid() {
        if (!(cellSize > 0) || !Double.isFinite(cellSize)) {
            // degenerate parameters: keep an empty grid, apply() falls back to the all-pairs loop
            grid.clear();
            return;
        }
        grid.clear();
        for (Point point : allPoints) {
            grid.computeIfAbsent(cellKey(point.getPosition().getX(), point.getPosition().getY()), k -> new ArrayList<>()).add(point);
        }
    }

    private long cellKey(double x, double y) {
        int cellX = (int) Math.floor(x / cellSize);
        int cellY = (int) Math.floor(y / cellSize);
        return (((long) cellX) << 32) ^ (cellY & 0xffffffffL);
    }

    @Override
    public Vector2D apply(V vertex, Point point, LayoutContext<V, E> layoutContext) {
        Vector2D resultingForce = new Vector2D();
        int thisVertexDegree = point.getPointVertexDegree();
        if (grid.isEmpty() && allPoints.length > 0) {
            // fallback for degenerate cell size: all-pairs interaction
            for (Point otherPoint : allPoints) {
                if (otherPoint != point) {
                    linearRepulsionBetweenPoints(resultingForce, thisVertexDegree, point, otherPoint, layoutContext);
                }
            }
            return resultingForce;
        }
        int cellX = (int) Math.floor(point.getPosition().getX() / cellSize);
        int cellY = (int) Math.floor(point.getPosition().getY() / cellSize);
        // all points within the interaction radius are in the 3x3 cells around the point's cell
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Point> cellPoints = grid.get((((long) (cellX + dx)) << 32) ^ ((cellY + dy) & 0xffffffffL));
                if (cellPoints == null) {
                    continue;
                }
                for (Point otherPoint : cellPoints) {
                    if (otherPoint != point) {
                        linearRepulsionBetweenPoints(resultingForce, thisVertexDegree, point, otherPoint, layoutContext);
                    }
                }
            }
        }
        return resultingForce;
    }

    private void linearRepulsionBetweenPoints(
            Vector2D resultingForce,
            int vertexDegree,
            Point point,
            Point otherPoint,
            LayoutContext<V, E> layoutContext
    ) {
        // The force goes from the otherPoint to the point (repulsion); computed on doubles directly
        // to avoid allocating an intermediate Vector2D in this hot loop
        double forceX = point.getPosition().getX() - otherPoint.getPosition().getX();
        double forceY = point.getPosition().getY() - otherPoint.getPosition().getY();
        double magnitude = Math.sqrt(forceX * forceX + forceY * forceY);
        if (magnitude < repulsionZoneRadius) {
            if (magnitude != 0) {
                //check distance against 2 * pointSize, imagine that the two points are touching edge to edge,
                // the distance between centers will be 2 * pointSize
                // we want to check against that limit to know if points are too close to each other
                double forceIntensity = magnitude <= 2 * pointSizeRecord.getPointSize() ? forceIntensityWithOverlap : forceIntensityNoOverlap / magnitude;

                double intensity = forceIntensity
                    * (vertexDegree + 1)
                    * (otherPoint.getPointVertexDegree() + 1)
                    / magnitude;

                resultingForce.add(forceX * intensity, forceY * intensity);
            } else {
                resultingForce.add(RandomForce.getRandomForce(layoutContext.getRandomGeneratorForForces()));
            }
        }
    }
}
