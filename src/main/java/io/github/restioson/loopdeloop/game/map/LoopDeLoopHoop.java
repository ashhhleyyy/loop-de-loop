package io.github.restioson.loopdeloop.game.map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

public class LoopDeLoopHoop {
    public BlockPos centre;
    public int radius;

    public LoopDeLoopHoop(BlockPos centre, int radius) {
        this.centre = centre;
        this.radius = radius;
    }

    public boolean intersectsSegment(Vec3d begin, Vec3d end) {
        Vec3d intersection = lineIntersectsPlane(begin, end, centre.getZ() + 0.5);

        if (intersection == null) {
            return false; // No intersection - line parallel
        }

        // radius - 1 is to avoid allowing people to go on top of corners
        return this.contains(new BlockPos(intersection));
    }

    public boolean contains(BlockPos pos) {
        int adjRadius = this.radius - 1; // radius - 1 is to avoid allowing people to go on top of corners
        int dx = pos.getX() - this.centre.getX();
        int dy = pos.getY() - this.centre.getY();
        return pos.getZ() == this.centre.getZ() && (dx * dx + dy * dy <= adjRadius * adjRadius);
    }

    @Nullable
    public static Vec3d lineIntersectsPlane(Vec3d origin, Vec3d target, double planeZ) {
        Vec3d ray = target.subtract(origin);
        if (Math.abs(ray.z) <= 1e-5) {
            return null;
        }

        double distanceAlongRay = (planeZ - origin.z) / ray.z;
        double distanceAlongRay2 = distanceAlongRay * distanceAlongRay;
        double rayLength2 = ray.lengthSquared();

        if (distanceAlongRay < 0.0 || distanceAlongRay2 >= rayLength2) {
            return null;
        }

        return origin.add(ray.normalize().multiply(distanceAlongRay));
    }
}
