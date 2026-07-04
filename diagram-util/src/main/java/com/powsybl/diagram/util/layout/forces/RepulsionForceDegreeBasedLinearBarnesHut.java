/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.diagram.util.layout.forces;

import com.powsybl.commons.ref.Ref;
import com.powsybl.diagram.util.layout.geometry.*;

/**
 * A linear repulsion force dependent on the number of edges of a node (same as {@link RepulsionForceDegreeBasedLinear}), but uses
 * a quadtree to speedup calculations by approximating far away points as their barycenters
 * @author Nathan Dissoubray {@literal <nathan.dissoubray at rte-france.com>}
 */
public class RepulsionForceDegreeBasedLinearBarnesHut<V, E> extends AbstractByEdgeNumberForce<V, E> {
    private final double forceIntensity;
    private final double barnesHutThetaSquare;
    private final Ref<Quadtree> quadtreeContainer;

    public RepulsionForceDegreeBasedLinearBarnesHut(double forceIntensity, double barnesHutTheta, Ref<Quadtree> quadtreeContainer) {
        this.forceIntensity = forceIntensity;
        // the acceptance criterion is evaluated on squared values, to avoid one square root per visited quadtree node
        this.barnesHutThetaSquare = barnesHutTheta * barnesHutTheta;
        this.quadtreeContainer = quadtreeContainer;
    }

    public Vector2D apply(V vertex, Point point, LayoutContext<V, E> layoutContext) {
        Vector2D resultingForce = new Vector2D();
        Quadtree quadtree = quadtreeContainer.get();
        BoundingBox rootBb = quadtree.getBoundingBox();
        // bounding box might not be square, this will work best for shapes that are not too long
        // could also test by using the diagonal width (using square root), might be faster as it will be tighter (but longer to calculate too)
        double width = Math.max(rootBb.getWidth(), rootBb.getHeight());
        // Assume the quadtree is built based on isEffectFromFixedNodes (ie with the fixed points in it or not)
        applyRepulsionFromNode(
            quadtree,
            quadtree.getRootIndex(),
            point,
            width * width,
            resultingForce
        );
        return resultingForce;
    }

    private void linearRepulsionBetweenPoints(
            Vector2D resultingForce,
            Point point,
            Point otherPoint,
            double deltaX,
            double deltaY,
            double distanceSquare
    ) {
        // The force goes from the otherPoint to the point (repulsion)
        // divide by magnitude^2 because the force multiplies the unit vector by something/magnitude
        // the unit vector is Vector/magnitude, thus the force is Vector/magnitude * something/magnitude, thus Vector/magnitude^2
        // if we just use the vector and not the unit vector, points that are further away will have the same influence as points that are close
        // this is easy to explain as the formula is Vector * k * deg(n1) * deg(n2)/distance
        // which would be UnitVector * k * deg(n1) * deg(n2)
        // all UnitVector will have the same magnitude of 1, giving only the direction, thus the force becomes dependant only on the degree of the nodes
        // the name "linear" is a bit misleading, as its technically inverse linear (1 / distance)
        double intensity = forceIntensity
            * (point.getPointVertexDegree() + 1)
            * (otherPoint.getMass())
            / distanceSquare; // no need to check division by 0, applyRepulsionFromNode already does that
        resultingForce.add(deltaX * intensity, deltaY * intensity);
    }

    /**
     * Recursively descend into child nodes of the quadtree and accumulate into resultingForce the repulsion of all
     * the points / barycenters that a given point has to interact with.<br>
     * This uses a decision criteria, we use the barycenter of a node of the quadtree instead of all its points if the width of the barycenter node
     * is smaller than the barnesHutTheta * the distance between point and the barycenter. The criterion is evaluated
     * on squared values (width² &lt; θ² · distance²), which is equivalent for non-negative values and avoids
     * computing a square root for every visited node.
     * @param quadtree the quadtree containing the points / barycenters
     * @param nodeIndex the index of the node we are considering approximating all the points it contains to its barycenter
     * @param point the point we want to compute the repulsion force for
     * @param nodeWidthSquare the squared width of the node corresponding to nodeIndex
     * @param resultingForce the force vector to accumulate into
     */
    private void applyRepulsionFromNode(
            Quadtree quadtree,
            int nodeIndex,
            Point point,
            double nodeWidthSquare,
            Vector2D resultingForce
    ) {
        Quadtree.QuadtreeNode thisNode = quadtree.getNodes()[nodeIndex];
        Point barycenter = thisNode.getNodeBarycenter();
        double deltaX = point.getPosition().getX() - barycenter.getPosition().getX();
        double deltaY = point.getPosition().getY() - barycenter.getPosition().getY();
        double distanceSquare = deltaX * deltaX + deltaY * deltaY;
        // Check the theta parameter ie width / distance < theta
        // if the node is a leaf, interact with the point directly (there is no approximation for a single point)
        if (nodeWidthSquare < barnesHutThetaSquare * distanceSquare || thisNode.isLeaf()) {
            if (distanceSquare != 0) {
                linearRepulsionBetweenPoints(resultingForce, point, barycenter, deltaX, deltaY, distanceSquare);
            }
        } else {
            // the child nodes have half the width of this node, ie a quarter of the squared width
            double childNodeWidthSquare = nodeWidthSquare / 4;
            for (int childIndex : thisNode.getRealChildrenNodeIndex()) {
                applyRepulsionFromNode(quadtree, childIndex, point, childNodeWidthSquare, resultingForce);
            }
        }
    }
}
