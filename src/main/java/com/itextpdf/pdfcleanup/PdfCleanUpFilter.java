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


import com.itextpdf.io.image.ImageFactory;
import com.itextpdf.kernel.geom.AffineTransform;
import com.itextpdf.kernel.geom.BezierCurve;
import com.itextpdf.kernel.geom.Line;
import com.itextpdf.kernel.geom.LineSegment;
import com.itextpdf.kernel.geom.Matrix;
import com.itextpdf.kernel.geom.NoninvertibleTransformException;
import com.itextpdf.kernel.geom.Point;
import com.itextpdf.kernel.geom.Point2D;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.geom.Subpath;
import com.itextpdf.kernel.parser.ImageRenderInfo;
import com.itextpdf.kernel.parser.PathRenderInfo;
import com.itextpdf.kernel.parser.TextRenderInfo;
import com.itextpdf.kernel.parser.clipper.Clipper;
import com.itextpdf.kernel.parser.clipper.Clipper.ClipType;
import com.itextpdf.kernel.parser.clipper.Clipper.EndType;
import com.itextpdf.kernel.parser.clipper.Clipper.JoinType;
import com.itextpdf.kernel.parser.clipper.Clipper.PolyFillType;
import com.itextpdf.kernel.parser.clipper.Clipper.PolyType;
import com.itextpdf.kernel.parser.clipper.ClipperBridge;
import com.itextpdf.kernel.parser.clipper.ClipperOffset;
import com.itextpdf.kernel.parser.clipper.DefaultClipper;
import com.itextpdf.kernel.parser.clipper.Paths;
import com.itextpdf.kernel.parser.clipper.PolyTree;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfNumber;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfTextArray;
import com.itextpdf.kernel.pdf.canvas.PdfCanvasConstants;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffConstants;

public class PdfCleanUpFilter {
    private static final Color cleanedAreaFillColor = Color.WHITE;
    private static final double circleApproximationConst = 0.55191502449;

    private List<Rectangle> regions;

    public PdfCleanUpFilter(List<Rectangle> regions) {
        this.regions = regions;
    }

    public PdfArray filterText(TextRenderInfo text) {
        PdfTextArray textArray = new PdfTextArray();

        if (isTextNotToBeCleaned(text)) {
            return new PdfArray(text.getPdfString());
        }

        for (TextRenderInfo ri : text.getCharacterRenderInfos()) {
            if (isTextNotToBeCleaned(ri)) {
                textArray.add(ri.getPdfString());
            } else {
                textArray.add(new PdfNumber(
                        -ri.getUnscaledWidth() * 1000f / ( text.getFontSize() * text.getHorizontalScaling()/100 )
                ));
            }
        }

        return textArray;
    }

    public PdfStream filterImage(ImageRenderInfo image) {
        List<Rectangle> areasToBeCleaned = getImageAreasToBeCleaned(image);
        if (areasToBeCleaned == null) {
            return null;
        }

        byte[] filteredImageBytes;
        try {
            byte[] originalImageBytes = image.getImage().getImageBytes(); // TODO for TIFF, PNG and LZW images this doesn't work. Fix it, when Tiff/PngWriter are implemented
            filteredImageBytes = processImage(originalImageBytes, areasToBeCleaned);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return new PdfImageXObject(ImageFactory.getImage(filteredImageBytes)).getPdfObject();
    }

    public com.itextpdf.kernel.geom.Path filterStrokePath(PathRenderInfo path) {
        PdfArray dashPattern = path.getLineDashPattern();
        LineDashPattern lineDashPattern = new LineDashPattern(dashPattern.getAsArray(0), dashPattern.getAsFloat(1));

        return filterStrokePath(path.getPath(), path.getCtm(), path.getLineWidth(), path.getLineCapStyle(),
                path.getLineJoinStyle(), path.getMiterLimit(), lineDashPattern);
    }

    public com.itextpdf.kernel.geom.Path filterFillPath(PathRenderInfo path, int fillingRule) {
        return filterFillPath(path.getPath(), path.getCtm(), fillingRule);
    }


    private boolean isTextNotToBeCleaned(TextRenderInfo renderInfo) {
        Point2D[] textRect = getTextRectangle(renderInfo);

        for (Rectangle region : regions) {
            Point2D[] redactRect = getRectangleVertices(region);

            if (checkIfRectanglesIntersect(textRect, redactRect)) {
                return false;
            }
        }

        return true;
    }

    private boolean checkIfRectanglesIntersect(Point2D[] rect1, Point2D[] rect2) {
        Clipper clipper = new DefaultClipper();
        ClipperBridge.addRectToClipper(clipper, rect1, PolyType.SUBJECT);
        ClipperBridge.addRectToClipper(clipper, rect2, PolyType.CLIP);

        Paths paths = new Paths();
        clipper.execute(ClipType.INTERSECTION, paths, PolyFillType.NON_ZERO, PolyFillType.NON_ZERO);

        return !paths.isEmpty();
    }


    /**
     * Calculates intersection of the image and the render filter region in the coordinate system relative to the image.
     *
     * @return <code>null</code> if the image is fully covered and therefore is completely cleaned, {@link java.util.List} of
     * {@link Rectangle} objects otherwise.
     */
    private List<Rectangle> getImageAreasToBeCleaned(ImageRenderInfo image) {
        Rectangle imageRect = calcImageRect(image);
        if (imageRect == null) {
            return null;
        }

        List<Rectangle> areasToBeCleaned = new ArrayList<>();

        for (Rectangle region : regions) {
            Rectangle intersectionRect = getRectanglesIntersection(imageRect, region);

            if (intersectionRect != null) {
                if (imageRect.equals(intersectionRect)) { // true if the image is completely covered
                    return null;
                }

                areasToBeCleaned.add(transformRectIntoImageCoordinates(intersectionRect, image.getImageCtm()));
            }
        }

        return areasToBeCleaned;
    }

    /**
     * @return Image boundary rectangle in device space.
     */
    private Rectangle calcImageRect(ImageRenderInfo renderInfo) {
        Matrix ctm = renderInfo.getImageCtm();

        if (ctm == null) {
            return null;
        }

        Point2D[] points = transformPoints(ctm, false,
                new Point(0, 0), new Point(0, 1),
                new Point(1, 0), new Point(1, 1));

        return getAsRectangle(points[0], points[1], points[2], points[3]);
    }

    /**
     * Transforms the given Rectangle into the image coordinate system which is [0,1]x[0,1] by default
     */
    private Rectangle transformRectIntoImageCoordinates(Rectangle rect, Matrix imageCtm) {
        Point2D[] points = transformPoints(imageCtm, true, new Point(rect.getLeft(), rect.getBottom()),
                new Point(rect.getLeft(), rect.getTop()),
                new Point(rect.getRight(), rect.getBottom()),
                new Point(rect.getRight(), rect.getTop()));
        return getAsRectangle(points[0], points[1], points[2], points[3]);
    }

    private byte[] processImage(byte[] imageBytes, List<Rectangle> areasToBeCleaned) {
        if (areasToBeCleaned.isEmpty()) {
            return imageBytes;
        }

        try {
            BufferedImage image = Imaging.getBufferedImage(imageBytes);
            ImageInfo imageInfo = Imaging.getImageInfo(imageBytes);
            cleanImage(image, areasToBeCleaned);

            // Apache can only read JPEG, so we should use awt for writing in this format
            if (imageInfo.getFormat() == ImageFormats.JPEG) {
                return getJPGBytes(image);
            } else {
                Map<String, Object> params = new HashMap<String, Object>();

                if (imageInfo.getFormat() == ImageFormats.TIFF) {
                    params.put(ImagingConstants.PARAM_KEY_COMPRESSION, TiffConstants.TIFF_COMPRESSION_LZW);
                }

                return Imaging.writeImageToBytes(image, imageInfo.getFormat(), params);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void cleanImage(BufferedImage image, List<Rectangle> areasToBeCleaned) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(cleanedAreaFillColor);

        // A rectangle in the areasToBeCleaned list is treated to be in standard [0, 1]x[0,1] image space
        // (y varies from bottom to top and x from left to right), so we should scale the rectangle and also
        // invert and shear the y axe
        for (Rectangle rect : areasToBeCleaned) {
            int scaledBottomY = (int) Math.ceil(rect.getBottom() * image.getHeight());
            int scaledTopY = (int) Math.floor(rect.getTop() * image.getHeight());

            int x = (int) Math.ceil(rect.getLeft() * image.getWidth());
            int y = scaledTopY * -1 + image.getHeight();
            int width = (int) Math.floor(rect.getRight() * image.getWidth()) - x;
            int height = scaledTopY - scaledBottomY;

            graphics.fillRect(x, y, width, height);
        }

        graphics.dispose();
    }

    private byte[] getJPGBytes(BufferedImage image) {
        ByteArrayOutputStream outputStream = null;

        try {
            ImageWriter jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam jpgWriteParam = jpgWriter.getDefaultWriteParam();
            jpgWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            jpgWriteParam.setCompressionQuality(1.0f);

            outputStream = new ByteArrayOutputStream();
            jpgWriter.setOutput(new MemoryCacheImageOutputStream((outputStream)));
            IIOImage outputImage = new IIOImage(image, null, null);

            jpgWriter.write(null, outputImage, jpgWriteParam);
            jpgWriter.dispose();
            outputStream.flush();

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            closeOutputStream(outputStream);
        }
    }


    private com.itextpdf.kernel.geom.Path filterStrokePath(com.itextpdf.kernel.geom.Path sourcePath, Matrix ctm,
                                                           float lineWidth, int lineCapStyle, int lineJoinStyle,
                                                           float miterLimit, LineDashPattern lineDashPattern) {
        com.itextpdf.kernel.geom.Path path = sourcePath;
        JoinType joinType = ClipperBridge.getJoinType(lineJoinStyle);
        EndType endType = ClipperBridge.getEndType(lineCapStyle);

        if (lineDashPattern != null) {
            if (!lineDashPattern.isSolid()) {
                path = LineDashPattern.applyDashPattern(path, lineDashPattern);
            }
        }

        ClipperOffset offset = new ClipperOffset(miterLimit, PdfCleanUpTool.arcTolerance * PdfCleanUpTool.floatMultiplier);
        List<Subpath> degenerateSubpaths = ClipperBridge.addPath(offset, path, joinType, endType);

        PolyTree resultTree = new PolyTree();
        offset.execute(resultTree, lineWidth * PdfCleanUpTool.floatMultiplier / 2);
        com.itextpdf.kernel.geom.Path offsetedPath = ClipperBridge.convertToPath(resultTree);

        if (degenerateSubpaths.size() > 0) {
            if (endType == EndType.OPEN_ROUND) {
                List<Subpath> circles = convertToCircles(degenerateSubpaths, lineWidth / 2);
                offsetedPath.addSubpaths(circles);
            } else if (endType == EndType.OPEN_SQUARE && lineDashPattern != null) {
                List<Subpath> squares = convertToSquares(degenerateSubpaths, lineWidth, sourcePath);
                offsetedPath.addSubpaths(squares);
            }
        }

        return filterFillPath(offsetedPath, ctm, PdfCanvasConstants.FillingRule.NONZERO_WINDING);
    }

    /**
     * Note: this method will close all unclosed subpaths of the passed path.
     *
     * @param fillingRule If the subpath is contour, pass any value.
     */
    protected com.itextpdf.kernel.geom.Path filterFillPath(com.itextpdf.kernel.geom.Path path, Matrix ctm, int fillingRule) {
        path.closeAllSubpaths();

        Clipper clipper = new DefaultClipper();
        ClipperBridge.addPath(clipper, path, PolyType.SUBJECT);

        for (Rectangle rectangle : regions) {
            Point2D[] transfRectVertices = transformPoints(ctm, true, getRectangleVertices(rectangle));
            ClipperBridge.addRectToClipper(clipper, transfRectVertices, PolyType.CLIP);
        }

        PolyFillType fillType = PolyFillType.NON_ZERO;

        if (fillingRule == PdfCanvasConstants.FillingRule.EVEN_ODD) {
            fillType = PolyFillType.EVEN_ODD;
        }

        PolyTree resultTree = new PolyTree();
        clipper.execute(ClipType.DIFFERENCE, resultTree, fillType, PolyFillType.NON_ZERO);

        return ClipperBridge.convertToPath(resultTree);
    }

    /**
     * Converts specified degenerate subpaths to circles.
     * Note: actually the resultant subpaths are not real circles but approximated.
     *
     * @param radius Radius of each constructed circle.
     * @return {@link java.util.List} consisting of circles constructed on given degenerated subpaths.
     */
    private static List<Subpath> convertToCircles(List<Subpath> degenerateSubpaths, double radius) {
        List<Subpath> circles = new ArrayList<Subpath>(degenerateSubpaths.size());

        for (Subpath subpath : degenerateSubpaths) {
            BezierCurve[] circleSectors = approximateCircle(subpath.getStartPoint(), radius);

            Subpath circle = new Subpath();
            circle.addSegment(circleSectors[0]);
            circle.addSegment(circleSectors[1]);
            circle.addSegment(circleSectors[2]);
            circle.addSegment(circleSectors[3]);

            circles.add(circle);
        }

        return circles;
    }

    /**
     * Converts specified degenerate subpaths to squares.
     * Note: the list of degenerate subpaths should contain at least 2 elements. Otherwise
     * we can't determine the direction which the rotation of each square depends on.
     *
     * @param squareWidth Width of each constructed square.
     * @param sourcePath The path which dash pattern applied to. Needed to calc rotation angle of each square.
     * @return {@link java.util.List} consisting of squares constructed on given degenerated subpaths.
     */
    private static List<Subpath> convertToSquares(List<Subpath> degenerateSubpaths, double squareWidth, com.itextpdf.kernel.geom.Path sourcePath) {
        List<Point2D> pathApprox = getPathApproximation(sourcePath);

        if (pathApprox.size() < 2) {
            return Collections.EMPTY_LIST;
        }

        Iterator<Point2D> approxIter = pathApprox.iterator();
        Point2D approxPt1 = approxIter.next();
        Point2D approxPt2 = approxIter.next();
        StandardLine line = new StandardLine(approxPt1, approxPt2);

        List<Subpath> squares = new ArrayList<Subpath>(degenerateSubpaths.size());
        float widthHalf = (float) squareWidth / 2;

        for (int i = 0; i < degenerateSubpaths.size(); ++i) {
            Point2D point = degenerateSubpaths.get(i).getStartPoint();

            while (!line.contains(point)) {
                approxPt1 = approxPt2;
                approxPt2 = approxIter.next();
                line = new StandardLine(approxPt1, approxPt2);
            }

            double slope = line.getSlope();
            double angle;

            if (slope != Float.POSITIVE_INFINITY) {
                angle = Math.atan(slope);
            } else {
                angle = Math.PI / 2;
            }

            squares.add(constructSquare(point, widthHalf, angle));
        }

        return squares;
    }

    private static List<Point2D> getPathApproximation(com.itextpdf.kernel.geom.Path path) {
        List<Point2D> approx = new ArrayList<Point2D>() {
            @Override
            public boolean addAll(Collection<? extends Point2D> c) {
                Point2D prevPoint = (size() - 1 < 0 ? null : get(size() - 1));
                boolean ret = false;

                for (Point2D pt : c) {
                    if (!pt.equals(prevPoint)) {
                        add(pt);
                        prevPoint = pt;
                        ret = true;
                    }
                }

                return true;
            }
        };

        for (Subpath subpath : path.getSubpaths()) {
            approx.addAll(subpath.getPiecewiseLinearApproximation());
        }

        return approx;
    }

    private static Subpath constructSquare(Point2D squareCenter, double widthHalf, double rotationAngle) {
        // Orthogonal square is the square with sides parallel to one of the axes.
        Point2D[] ortogonalSquareVertices = {
                new Point2D.Double(-widthHalf, -widthHalf),
                new Point2D.Double(-widthHalf, widthHalf),
                new Point2D.Double(widthHalf, widthHalf),
                new Point2D.Double(widthHalf, -widthHalf)
        };

        Point2D[] rotatedSquareVertices = getRotatedSquareVertices(ortogonalSquareVertices, rotationAngle, squareCenter);

        Subpath square = new Subpath();
        square.addSegment(new Line(rotatedSquareVertices[0], rotatedSquareVertices[1]));
        square.addSegment(new Line(rotatedSquareVertices[1], rotatedSquareVertices[2]));
        square.addSegment(new Line(rotatedSquareVertices[2], rotatedSquareVertices[3]));
        square.addSegment(new Line(rotatedSquareVertices[3], rotatedSquareVertices[0]));

        return square;
    }

    private static Point2D[] getRotatedSquareVertices(Point2D[] orthogonalSquareVertices, double angle, Point2D squareCenter) {
        Point2D[] rotatedSquareVertices = new Point2D[orthogonalSquareVertices.length];

        AffineTransform.getRotateInstance((float) angle).
                transform(orthogonalSquareVertices, 0, rotatedSquareVertices, 0, rotatedSquareVertices.length);
        AffineTransform.getTranslateInstance((float)squareCenter.getX(), (float)squareCenter.getY()).
                transform(rotatedSquareVertices, 0, rotatedSquareVertices, 0, orthogonalSquareVertices.length);

        return rotatedSquareVertices;
    }

    private static BezierCurve[] approximateCircle(Point2D center, double radius) {
        // The circle is split into 4 sectors. Arc of each sector
        // is approximated  with bezier curve separately.
        BezierCurve[] approximation = new BezierCurve[4];
        double x = center.getX();
        double y = center.getY();

        approximation[0] = new BezierCurve(Arrays.asList(
                (Point2D) new Point2D.Double(x, y + radius),
                new Point2D.Double(x + radius * circleApproximationConst, y + radius),
                new Point2D.Double(x + radius, y + radius * circleApproximationConst),
                new Point2D.Double(x + radius, y)));

        approximation[1] = new BezierCurve(Arrays.asList(
                (Point2D) new Point2D.Double(x + radius, y),
                new Point2D.Double(x + radius, y - radius * circleApproximationConst),
                new Point2D.Double(x + radius * circleApproximationConst, y - radius),
                new Point2D.Double(x, y - radius)));

        approximation[2] = new BezierCurve(Arrays.asList(
                (Point2D) new Point2D.Double(x, y - radius),
                new Point2D.Double(x - radius * circleApproximationConst, y - radius),
                new Point2D.Double(x - radius, y - radius * circleApproximationConst),
                new Point2D.Double(x - radius, y)));

        approximation[3] = new BezierCurve(Arrays.asList(
                (Point2D) new Point2D.Double(x - radius, y),
                new Point2D.Double(x - radius, y + radius * circleApproximationConst),
                new Point2D.Double(x - radius * circleApproximationConst, y + radius),
                new Point2D.Double(x, y + radius)));

        return approximation;
    }


    private Point2D[] transformPoints(Matrix transformationMatrix, boolean inverse, Point2D... points) {
        AffineTransform t = new AffineTransform(transformationMatrix.get(Matrix.I11), transformationMatrix.get(Matrix.I12),
                transformationMatrix.get(Matrix.I21), transformationMatrix.get(Matrix.I22),
                transformationMatrix.get(Matrix.I31), transformationMatrix.get(Matrix.I32));
        Point2D[] transformed = new Point2D[points.length];

        if (inverse) {
            try {
                t = t.createInverse();
            } catch (NoninvertibleTransformException e) {
                throw new RuntimeException(e);
            }
        }

        t.transform(points, 0, transformed, 0, points.length);

        return transformed;
    }

    private Point2D[] getTextRectangle(TextRenderInfo renderInfo) {
        LineSegment ascent = renderInfo.getAscentLine();
        LineSegment descent = renderInfo.getDescentLine();

        return new Point2D[] {
                new Point2D.Float(ascent.getStartPoint().get(0), ascent.getStartPoint().get(1)),
                new Point2D.Float(ascent.getEndPoint().get(0), ascent.getEndPoint().get(1)),
                new Point2D.Float(descent.getEndPoint().get(0), descent.getEndPoint().get(1)),
                new Point2D.Float(descent.getStartPoint().get(0), descent.getStartPoint().get(1)),
        };
    }

    private Point2D[] getRectangleVertices(Rectangle rect) {
        Point2D[] points = {
                new Point2D.Double(rect.getLeft(), rect.getBottom()),
                new Point2D.Double(rect.getRight(), rect.getBottom()),
                new Point2D.Double(rect.getRight(), rect.getTop()),
                new Point2D.Double(rect.getLeft(), rect.getTop())
        };

        return points;
    }

    private Rectangle getAsRectangle(Point2D p1, Point2D p2, Point2D p3, Point2D p4) {
        List<Double> xs = Arrays.asList(p1.getX(), p2.getX(), p3.getX(), p4.getX());
        List<Double> ys = Arrays.asList(p1.getY(), p2.getY(), p3.getY(), p4.getY());

        double left = Collections.min(xs);
        double bottom = Collections.min(ys);
        double right = Collections.max(xs);
        double top = Collections.max(ys);

        return new Rectangle((float) left, (float) bottom, (float) (right - left), (float) (top - bottom));
    }

    private Rectangle getRectanglesIntersection(Rectangle rect1, Rectangle rect2) {
        float x1 = Math.max(rect1.getLeft(), rect2.getLeft());
        float y1 = Math.max(rect1.getBottom(), rect2.getBottom());
        float x2 = Math.min(rect1.getRight(), rect2.getRight());
        float y2 = Math.min(rect1.getTop(), rect2.getTop());
        return (x2 - x1 > 0 && y2 - y1 > 0)
                ? new Rectangle(x1, y1, x2 - x1, y2 - y1)
                : null;
    }


    private void closeOutputStream(OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Constants from the standard line representation: Ax+By+C
    private static class StandardLine {

        float A;
        float B;
        float C;

        StandardLine(Point2D p1, Point2D p2) {
            A = (float) (p2.getY() - p1.getY());
            B = (float) (p1.getX() - p2.getX());
            C = (float) (p1.getY() * (-B) - p1.getX() * A);
        }

        float getSlope() {
            if (B == 0) {
                return Float.POSITIVE_INFINITY;
            }

            return -A / B;
        }

        boolean contains(Point2D point) {
            return Float.compare(Math.abs(A * (float) point.getX() + B * (float) point.getY() + C), 0.1f) < 0;
        }
    }
}