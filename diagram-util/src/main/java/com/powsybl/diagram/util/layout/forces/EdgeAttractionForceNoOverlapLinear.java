/**
 * Copyright (c) 2025-2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.diagram.util.layout.forces;

import com.powsybl.diagram.util.layout.forces.util.NoOverlapPointSize;
import com.powsybl.diagram.util.layout.geometry.LayoutContext;
import com.powsybl.diagram.util.layout.geometry.Point;
import com.powsybl.diagram.util.layout.geometry.Vector2D;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

/**
 * An attraction force used to prevent overlapping points, given their pointSize
 * @author Nathan Dissoubray {@literal <nathan.dissoubray at rte-france.com>}
 */
public class EdgeAttractionForceNoOverlapLinear<V, E> implements Force<V, E> {
    private final double forceIntensity;
    private final NoOverlapPointSize pointSizeRecord;

    @Override
    public void init(LayoutContext<V, E> layoutContext) {
        pointSizeRecord.calculatePointSize(layoutContext.getAllPoints().size());
        layoutContext.cacheDegree();
    }

    public EdgeAttractionForceNoOverlapLinear(double forceIntensity, double pointSizeScale, double pointSizeOffset) {
        this.forceIntensity = forceIntensity;
        this.pointSizeRecord = new NoOverlapPointSize(pointSizeScale, pointSizeOffset);
    }

    @Override
    public Vector2D apply(V vertex, Point point, LayoutContext<V, E> layoutContext) {
        Vector2D resultingForce = new Vector2D();
        // iterate over the edges directly instead of using Graphs.neighborSetOf, which allocates a new set on each call
        // the graph is a SimpleGraph so there are no parallel edges nor loops: each edge gives a distinct neighbor
        SimpleGraph<V, DefaultEdge> simpleGraph = layoutContext.getSimpleGraph();
        for (DefaultEdge edge : simpleGraph.edgesOf(vertex)) {
            V otherVertex = Graphs.getOppositeVertex(simpleGraph, edge, vertex);
            Point otherPoint = layoutContext.getAllPoints().get(otherVertex);
            forceBetweenPoints(resultingForce, point, otherPoint);
        }
        return resultingForce;
    }

    private void forceBetweenPoints(Vector2D resultingForce, Point point, Point otherPoint) {
        // The force goes from the point to the otherPoint (attraction); computed on doubles directly
        // to avoid allocating an intermediate Vector2D in this hot loop
        double forceX = otherPoint.getPosition().getX() - point.getPosition().getX();
        double forceY = otherPoint.getPosition().getY() - point.getPosition().getY();
        double magnitude = Math.sqrt(forceX * forceX + forceY * forceY);
        // check that there is no overlap between the points
        if (magnitude > 2 * pointSizeRecord.getPointSize()) {
            resultingForce.add(forceX * forceIntensity, forceY * forceIntensity);
        }
    }
}

