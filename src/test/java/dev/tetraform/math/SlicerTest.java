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
        SliceFrame f = SliceFrame.zw(Math.toRadians(45));
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
        // An arbitrary orientation (as used by mobs) exercises the fully general hexagonal cuts.
        SliceFrame f = new SliceFrame();
        f.turnForwardTowards(0.4, 0.3, 0.8, Math.PI);
        f.rotateInSlice(0.7);
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
    void zwSlicesOfTesseractsAreBoxes() {
        // The reference camera keeps blocks box-shaped while rotating into W: every face must be a rectangle.
        for (double deg = 0; deg < 360; deg += 7.5) {
            SliceFrame f = SliceFrame.zw(Math.toRadians(deg));
            for (double off = -0.43; off <= 0.45; off += 0.17) {
                Vec4 c = new Vec4(0.5, 0.5, 0.5, 0.5);
                List<Face> faces = slice(f, f.depthOf(c) + off, new double[]{0, 0, 0, 0, 1, 1, 1, 1});
                assertFalse(faces.isEmpty());
                for (Face face : faces) {
                    assertEquals(4, face.n, "zw slices only produce rectangles (angle " + deg + ")");
                    assertOutward(face);
                }
                assertEquals(6, faces.size(), "a box has six faces (angle " + deg + ")");
            }
        }
    }

    @Test
    void zwQuarterTurnShowsXYW() {
        SliceFrame f = SliceFrame.zw(Math.PI / 2);
        assertTrue(f.isAxisAligned());
        Vec4 n = f.normal();
        assertEquals(-1.0, n.z(), 1e-12);
        assertEquals(0.0, n.w(), 1e-12);
        assertEquals(1.0, f.lookDirection(0f, 0f).w(), 1e-12, "facing +z before the turn means facing +w after it");
        assertEquals(Math.PI / 2, f.zwAngle(), 1e-12);
    }

    @Test
    void forwardMatchesReferenceCamera() {
        // Reference: forward = (sin xz, cos xz * cos zw, cos xz * sin zw) in (x, z, w); vanilla yaw turns the other way.
        for (double zw = -3; zw <= 3; zw += 0.5) {
            SliceFrame f = SliceFrame.zw(zw);
            for (float yaw = -180; yaw < 180; yaw += 15) {
                double r = Math.toRadians(yaw);
                Vec4 look = f.lookDirection(yaw, 0f);
                assertEquals(-Math.sin(r), look.x(), 1e-9);
                assertEquals(Math.cos(r) * Math.cos(zw), look.z(), 1e-9);
                assertEquals(Math.cos(r) * Math.sin(zw), look.w(), 1e-9);
            }
        }
    }

    @Test
    void mouseLookNeverChangesTheSlice() {
        for (double zw = 0; zw < 6.3; zw += 0.35) {
            SliceFrame f = SliceFrame.zw(zw);
            Vec4 n = f.normal();
            assertEquals(0.0, n.x(), 1e-12, "the hyperplane normal stays in the z-w plane");
            for (float yaw = -180; yaw < 180; yaw += 17) {
                for (float pitch = -90; pitch <= 90; pitch += 30) {
                    assertEquals(0.0, f.lookDirection(yaw, pitch).dot(n), 1e-9);
                }
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
