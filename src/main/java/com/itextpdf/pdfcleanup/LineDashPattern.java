/*
    This file is part of the iText (R) project.
    Copyright (c) 1998-2017 iText Group NV
    Authors: iText Software.

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License version 3
    as published by the Free Software Foundation with the addition of the
    following permission added to Section 15 as permitted in Section 7(a):
    FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
    ITEXT GROUP. ITEXT GROUP DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
    OF THIRD PARTY RIGHTS

    This program is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
    or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License
    along with this program; if not, see http://www.gnu.org/licenses or write to
    the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
    Boston, MA, 02110-1301 USA, or download the license from the following URL:
    http://itextpdf.com/terms-of-use/

    The interactive user interfaces in modified source and object code versions
    of this program must display Appropriate Legal Notices, as required under
    Section 5 of the GNU Affero General Public License.

    In accordance with Section 7(b) of the GNU Affero General Public License,
    a covered work must retain the producer line in every PDF that is created
    or manipulated using iText.

    You can be released from the requirements of the license by purchasing
    a commercial license. Buying such a license is mandatory as soon as you
    develop commercial activities involving the iText software without
    disclosing the source code of your own applications.
    These activities include: offering paid services to customers as an ASP,
    serving PDFs on the fly in a web application, shipping iText with a closed
    source product.

    For more information, please contact iText Software Corp. at this
    address: sales@itextpdf.com
 */
package com.itextpdf.pdfcleanup;


import com.itextpdf.kernel.geom.Path;
import com.itextpdf.kernel.geom.Point2D;
import com.itextpdf.kernel.geom.Subpath;
import com.itextpdf.kernel.pdf.PdfArray;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents the line dash pattern. The line dash pattern shall control the pattern
 * of dashes and gaps used to stroke paths. It shall be specified by a dash array and
 * a dash phase.
 */
public class LineDashPattern {
    private PdfArray dashArray;
    private float dashPhase;

    private int currentIndex;
    private int elemOrdinalNumber = 1;
    private DashArrayElem currentElem;

    /**
     * Creates new {@link LineDashPattern} object.
     * @param dashArray The dash array. See {@link #getDashArray()}
     * @param dashPhase The dash phase. See {@link #getDashPhase()}
     */
    public LineDashPattern(PdfArray dashArray, float dashPhase) {
        this.dashArray = new PdfArray(dashArray);
        this.dashPhase = dashPhase;
        initFirst(dashPhase);
    }

    /**
     * Getter for the dash array.
     *
     * The dash array’s elements is number that specify the lengths of
     * alternating dashes and gaps; the numbers are nonnegative. The
     * elements are expressed in user space units.
     *
     * @return The dash array.
     */
    public PdfArray getDashArray() {
        return dashArray;
    }

    /**
     * Setter for the dash array. See {@link #getDashArray()}
     * @param dashArray New dash array.
     */
    public void setDashArray(PdfArray dashArray) {
        this.dashArray = dashArray;
    }

    /**
     * Getter for the dash phase.
     *
     * The dash phase shall specify the distance into the dash pattern at which
     * to start the dash. The elements are expressed in user space units.
     *
     * @return The dash phase.
     */
    public float getDashPhase() {
        return dashPhase;
    }

    /**
     * Setter for the dash phase. See {@link #getDashArray()}
     * @param dashPhase New dash phase.
     */
    public void setDashPhase(float dashPhase) {
        this.dashPhase = dashPhase;
    }

    /**
     * Calculates and returns the next element which is either gap or dash.
     * @return The next dash array's element.
     */
    public DashArrayElem next() {
        DashArrayElem ret = currentElem;

        if (dashArray.size() > 0) {
            currentIndex = (currentIndex + 1) % dashArray.size();
            currentElem = new DashArrayElem(dashArray.getAsNumber(currentIndex).getFloatValue(),
                    isEven(++elemOrdinalNumber));
        }

        return ret;
    }

    /**
     * Resets the dash array so that the {@link #next()} method will start
     * from the beginning of the dash array.
     */
    public void reset() {
        currentIndex = 0;
        elemOrdinalNumber = 1;
        initFirst(dashPhase);
    }

    /**
     * Checks whether the dashed pattern is solid or not. It's solid when the
     * size of a dash array is even and sum of all the units off in the array
     * is 0.<br/>
     * For example: [3 0 4 0 5 0 6 0] (sum is 0), [3 0 4 0 5 1] (sum is 1).
     */
    public boolean isSolid() {
        if (dashArray.size() % 2 != 0) {
            return false;
        }

        float unitsOffSum = 0;

        for (int i = 1; i < dashArray.size(); i += 2) {
            unitsOffSum += dashArray.getAsNumber(i).getFloatValue();
        }

        return Float.compare(unitsOffSum, 0) == 0;
    }

    private void initFirst(float phase) {
        if (dashArray.size() > 0) {
            while (phase > 0) {
                phase -= dashArray.getAsNumber(currentIndex).getFloatValue();
                currentIndex = (currentIndex + 1) % dashArray.size();
                elemOrdinalNumber++;
            }

            if (phase < 0) {
                --elemOrdinalNumber;
                --currentIndex;
                currentElem = new DashArrayElem(-phase, isEven(elemOrdinalNumber));
            } else {
                currentElem = new DashArrayElem(dashArray.getAsNumber(currentIndex).getFloatValue(),
                        isEven(elemOrdinalNumber));
            }
        }
    }

    private boolean isEven(int num) {
        return (num % 2) == 0;
    }

    public class DashArrayElem {

        private float val;
        private boolean isGap;

        public DashArrayElem(float val, boolean isGap) {
            this.val = val;
            this.isGap = isGap;
        }

        public float getVal() {
            return val;
        }

        public void setVal(float val) {
            this.val = val;
        }

        public boolean isGap() {
            return isGap;
        }

        public void setGap(boolean isGap) {
            this.isGap = isGap;
        }
    }

    public static Path applyDashPattern(Path path, LineDashPattern lineDashPattern) {
        Set<Integer> modifiedSubpaths = new HashSet<Integer>(path.replaceCloseWithLine());
        Path dashedPath = new Path();
        int currentSubpath = 0;

        for (Subpath subpath : path.getSubpaths()) {
            List<Point2D> subpathApprox = subpath.getPiecewiseLinearApproximation();

            if (subpathApprox.size() > 1) {
                dashedPath.moveTo((float) subpathApprox.get(0).getX(), (float) subpathApprox.get(0).getY());
                float remainingDist = 0;
                boolean remainingIsGap = false;

                for (int i = 1; i < subpathApprox.size(); ++i) {
                    Point2D nextPoint = null;

                    if (remainingDist != 0) {
                        nextPoint = getNextPoint(subpathApprox.get(i - 1), subpathApprox.get(i), remainingDist);
                        remainingDist = applyDash(dashedPath, subpathApprox.get(i - 1), subpathApprox.get(i), nextPoint, remainingIsGap);
                    }

                    while (Float.compare(remainingDist, 0) == 0 && !dashedPath.getCurrentPoint().equals(subpathApprox.get(i))) {
                        LineDashPattern.DashArrayElem currentElem = lineDashPattern.next();
                        nextPoint = getNextPoint(nextPoint != null ? nextPoint : subpathApprox.get(i - 1), subpathApprox.get(i), currentElem.getVal());
                        remainingDist = applyDash(dashedPath, subpathApprox.get(i - 1), subpathApprox.get(i), nextPoint, currentElem.isGap());
                        remainingIsGap = currentElem.isGap();
                    }
                }

                // If true, then the line closing the subpath was explicitly added (see Path.ReplaceCloseWithLine).
                // This causes a loss of a visual effect of line join style parameter, so in this clause
                // we simply add overlapping dash (or gap, no matter), which continues the last dash and equals to
                // the first dash (or gap) of the path.
                if (modifiedSubpaths.contains(currentSubpath)) {
                    lineDashPattern.reset();
                    LineDashPattern.DashArrayElem currentElem = lineDashPattern.next();
                    Point2D nextPoint = getNextPoint(subpathApprox.get(0), subpathApprox.get(1), currentElem.getVal());
                    applyDash(dashedPath, subpathApprox.get(0), subpathApprox.get(1), nextPoint, currentElem.isGap());
                }
            }

            // According to PDF spec. line dash pattern should be restarted for each new subpath.
            lineDashPattern.reset();
            ++currentSubpath;
        }

        return dashedPath;
    }

    private static Point2D getNextPoint(Point2D segStart, Point2D segEnd, float dist) {
        Point2D vector = componentwiseDiff(segEnd, segStart);
        Point2D unitVector = getUnitVector(vector);

        return new Point2D.Float((float) (segStart.getX() + dist * unitVector.getX()),
                (float) (segStart.getY() + dist * unitVector.getY()));
    }

    private static Point2D componentwiseDiff(Point2D minuend, Point2D subtrahend) {
        return new Point2D.Float((float) (minuend.getX() - subtrahend.getX()),
                (float) (minuend.getY() - subtrahend.getY()));
    }

    private static Point2D getUnitVector(Point2D vector) {
        double vectorLength = getVectorEuclideanNorm(vector);
        return new Point2D.Float((float) (vector.getX() / vectorLength),
                (float) (vector.getY() / vectorLength));
    }

    private static double getVectorEuclideanNorm(Point2D vector) {
        return vector.distance(0, 0);
    }

    private static float applyDash(Path dashedPath, Point2D segStart, Point2D segEnd, Point2D dashTo, boolean isGap) {
        float remainingDist = 0;

        if (!liesOnSegment(segStart, segEnd, dashTo)) {
            remainingDist = (float) dashTo.distance(segEnd);
            dashTo = segEnd;
        }

        if (isGap) {
            dashedPath.moveTo((float) dashTo.getX(), (float) dashTo.getY());
        } else {
            dashedPath.lineTo((float) dashTo.getX(), (float) dashTo.getY());
        }

        return remainingDist;
    }

    private static boolean liesOnSegment(Point2D segStart, Point2D segEnd, Point2D point) {
        return point.getX() >= Math.min(segStart.getX(), segEnd.getX()) &&
                point.getX() <= Math.max(segStart.getX(), segEnd.getX()) &&
                point.getY() >= Math.min(segStart.getY(), segEnd.getY()) &&
                point.getY() <= Math.max(segStart.getY(), segEnd.getY());
    }
}