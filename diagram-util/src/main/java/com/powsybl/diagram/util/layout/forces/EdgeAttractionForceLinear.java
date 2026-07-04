/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.diagram.util.layout.forces;

import com.powsybl.diagram.util.layout.geometry.Point;
import com.powsybl.diagram.util.layout.geometry.Vector2D;
import com.powsybl.diagram.util.layout.geometry.LayoutContext;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

/**
 * A force that attracts a point towards the other points it has an edge with. The further away the points are, the stronger the force is.
 * The force tends towards 0 as points get closer.
 * @author Nathan Dissoubray {@literal <nathan.dissoubray at rte-france.com>}
 */
public class EdgeAttractionForceLinear<V, E> implements Force<V, E> {
    private final double forceIntensity;

    public EdgeAttractionForceLinear(double forceIntensity) {
        this.forceIntensity = forceIntensity;
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
        resultingForce.add(forceX * forceIntensity, forceY * forceIntensity);
    }
}

