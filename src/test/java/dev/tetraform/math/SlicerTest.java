package dev.tetraform.math;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SlicerTest {
    record Face(Direction4 dir, int n, float[] pos, float nx, float ny, float nz) {}

    private static List<Face> slice(SliceFrame f, double depth, double[] box) {
        Slicer s = new Slicer();
        s.setPlane(f, depth, 0, 0);
        List<Face> out = new ArrayList<>();
        s.slice(box[0], box[1], box[2], box[3], box[4], box[5], box[6], box[7], 0xFF, 0, 0, 0, 0,
                (dir, n, pos, uv, nx, ny, nz) -> out.add(new Face(dir, n, pos.clone(), nx, ny, nz)));
        return out;
    }

    private static double area(Face f) {
        // Newell's method for polygon area in 3D
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < f.n; i++) {
            int j = (i + 1) % f.n;
            double x1 = f.pos[i * 3], y1 = f.pos[i * 3 + 1], z1 = f.pos[i * 3 + 2];
            double x2 = f.pos[j * 3], y2 = f.pos[j * 3 + 1], z2 = f.pos[j * 3 + 2];
            ax += (y1 - y2) * (z1 + z2);
            ay += (z1 - z2) * (x1 + x2);
            az += (x1 - x2) * (y1 + y2);
        }
        return 0.5 * Math.sqrt(ax * ax + ay * ay + az * az);
    }

    /** Polygon winding normal must agree with the reported outward normal (so back-face culling is correct). */
    private static void assertOutward(Face f) {
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < f.n; i++) {
            int j = (i + 1) % f.n;
            double x1 = f.pos[i * 3], y1 = f.pos[i * 3 + 1], z1 = f.pos[i * 3 + 2];
            double x2 = f.pos[j * 3], y2 = f.pos[j * 3 + 1], z2 = f.pos[j * 3 + 2];
            ax += (y1 - y2) * (z1 + z2);
            ay += (z1 - z2) * (x1 + x2);
            az += (x1 - x2) * (y1 + y2);
        }
        double dot = ax * f.nx + ay * f.ny + az * f.nz;
        assertTrue(dot > 0, "face " + f.dir + " winding disagrees with its outward normal");
    }

    @Test
    void axisAlignedSliceOfTesseractIsACube() {
        SliceFrame f = new SliceFrame();
        List<Face> faces = slice(f, 0.5, new double[]{0, 0, 0, 0, 1, 1, 1, 1});
        Map<Direction4, Double> areas = new EnumMap<>(Direction4.class);
        for (Face face : faces) {
            areas.merge(face.dir, area(face), Double::sum);
            assertOutward(face);
        }
        assertEquals(6, faces.size(), "a w-slice of a tesseract is a cube with 6 faces");
        assertFalse(areas.containsKey(Direction4.ANA));
        assertFalse(areas.containsKey(Direction4.KATA));
        for (double a : areas.values()) assertEquals(1.0, a, 1e-6);
    }

    @Test
    void sliceMissesBoxOutsideDepth() {
        assertTrue(slice(new SliceFrame(), 1.5, new double[]{0, 0, 0, 0, 1, 1, 1, 1}).isEmpty());
    }

    @Test
    void tiltedSliceShowsWFaces() {
        SliceFrame f = new SliceFrame();
        f.rotateForwardIntoW(0f, Math.toRadians(45)); // yaw 0 => forward is +z, so rotate the z/w plane
        Vec4 center = new Vec4(0.5, 0.5, 0.5, 0.5);
        List<Face> faces = slice(f, f.depthOf(center), new double[]{0, 0, 0, 0, 1, 1, 1, 1});
        boolean sawW = faces.stream().anyMatch(x -> x.dir == Direction4.ANA || x.dir == Direction4.KATA);
        assertTrue(sawW, "a 45 degree slice must cut the W-facing cells");
        for (Face face : faces) assertOutward(face);
        // Top and bottom caps exist, each a rectangle 1 x sqrt(2)
        double top = faces.stream().filter(x -> x.dir == Direction4.UP).mapToDouble(SlicerTest::area).sum();
        assertEquals(Math.sqrt(2), top, 1e-6);
    }

    @Test
    void genericSliceGivesConvexClosedSolid() {
        SliceFrame f = new SliceFrame();
        f.rotateForwardIntoW(33f, Math.toRadians(27));
        f.rotateForwardIntoW(-71f, Math.toRadians(52));
        Vec4 c = new Vec4(0.5, 0.5, 0.5, 0.5);
        List<Face> faces = slice(f, f.depthOf(c) + 0.1, new double[]{0, 0, 0, 0, 1, 1, 1, 1});
        assertFalse(faces.isEmpty());
        // Divergence theorem: sum of area * normal over a closed surface is zero.
        double sx = 0, sy = 0, sz = 0;
        for (Face face : faces) {
            assertOutward(face);
            double a = area(face);
            sx += a * face.nx;
            sy += a * face.ny;
            sz += a * face.nz;
        }
        assertEquals(0, sx, 1e-5);
        assertEquals(0, sy, 1e-5);
        assertEquals(0, sz, 1e-5);
    }

    @Test
    void fourQuarterTurnsReturnToIdentity() {
        SliceFrame f = new SliceFrame();
        for (int i = 0; i < 4; i++) f.rotateForwardIntoW(0f, Math.PI / 2);
        assertTrue(f.approxEquals(new SliceFrame(), 1e-9));
    }

    @Test
    void quarterTurnLooksAlongW() {
        SliceFrame f = new SliceFrame();
        f.rotateForwardIntoW(0f, Math.PI / 2);
        Vec4 look = f.lookDirection(0f, 0f);
        assertEquals(1.0, look.w(), 1e-9, "after rotating forward into W by 90 degrees the player looks along +W");
        assertTrue(f.isAxisAligned() == false);
        assertEquals(0.0, f.normalW(), 1e-9);
    }

    @Test
    void mouseYawNeverChangesTheSlice() {
        SliceFrame f = new SliceFrame();
        f.rotateForwardIntoW(20f, 0.4);
        Vec4 n = f.normal();
        // Yaw and pitch are applied inside the slice: the look direction is always orthogonal to the normal.
        for (float yaw = -180; yaw < 180; yaw += 17) {
            for (float pitch = -90; pitch <= 90; pitch += 30) {
                assertEquals(0.0, f.lookDirection(yaw, pitch).dot(n), 1e-9);
            }
        }
    }

    @Test
    void turnTowardsReachesTarget() {
        SliceFrame f = new SliceFrame();
        for (int i = 0; i < 100; i++) f.turnForwardTowards(0.3, -0.2, 0.9, Math.toRadians(10));
        double[] fw = f.forward();
        double l = Math.sqrt(0.09 + 0.04 + 0.81);
        assertEquals(0.3 / l, fw[0], 1e-6);
        assertEquals(-0.2 / l, fw[1], 1e-6);
        assertEquals(0.9 / l, fw[2], 1e-6);
    }

    @Test
    void blockPosPacking() {
        BlockPos4 p = new BlockPos4(-12345, 200, 54321, -77);
        assertEquals(p, BlockPos4.of(p.asLong()));
    }
}
