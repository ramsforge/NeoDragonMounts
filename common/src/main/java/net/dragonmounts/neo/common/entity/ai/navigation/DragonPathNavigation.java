package net.dragonmounts.neo.common.entity.ai.navigation;

import net.dragonmounts.neo.common.entity.dragon.ServerDragonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class DragonPathNavigation extends PathNavigation {
    /// PathNavigation sizes the A* budget as `followRange * 16`, which assumes a flat ground search.
    /// DragonNodeEvaluator also expands UP and DOWN, so the frontier grows with the cube of the
    /// radius rather than the square, while the budget is still scaled as if it were flat. Every
    /// node costs ~10 neighbour evaluations that each run a 27-block danger scan, so at the default
    /// 64-block follow range (1024 nodes) a single path is tens of thousands of block lookups - and
    /// it is recomputed every tick, because MoveToTargetSink re-paths once the walk target drifts a
    /// single block and FollowOwner points that target at a live EntityTracker on the owner, who
    /// moves far more than a block per tick while flying. Measured on a live 26.1.2 server: two
    /// dragons held the server thread at 100% of a core with the tick loop ~29s behind. A dragon
    /// that cannot find a walking route inside this many nodes should be flying there instead.
    private static final int MAX_VISITED_NODES = 128;

    public final ServerDragonEntity dragon;

    public DragonPathNavigation(ServerDragonEntity dragon, Level level) {
        super(dragon, level);
        this.setCanFloat(true);
        this.dragon = dragon;
    }

    /// New abstract method in 1.21.8. GoalUtils#hasGroundPathNavigation previously tested
    /// `instanceof GroundPathNavigation`, and this class extends PathNavigation directly, so it
    /// evaluated to false. Returning false preserves the old behaviour exactly.
    @Override
    public boolean canNavigateGround() {
        return false;
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new DragonNodeEvaluator();
        return new PathFinder(this.nodeEvaluator, Math.min(maxVisitedNodes, MAX_VISITED_NODES));
    }

    @Override
    protected boolean canMoveDirectly(Vec3 posVec31, Vec3 posVec32) {
        return this.dragon.isFlying() && isClearForMovementBetween(this.dragon, posVec31, posVec32, true);
    }

    @Override
    protected boolean canUpdatePath() {
        return true;
    }

    @Override
    protected Vec3 getTempMobPos() {
        return this.mob.position();
    }

    @Nullable
    @Override
    public Path createPath(Entity entity, int accuracy) {
        return this.createPath(entity.blockPosition(), accuracy);
    }

    @Override
    public void tick() {
        this.tick++;
        if (this.hasDelayedRecomputation) {
            this.recomputePath();
        }
        if (!this.isDone()) {
            if (this.canUpdatePath()) {
                this.followThePath();
            } else if (this.path != null && !this.path.isDone()) {
                var next = this.path.getNextEntityPos(this.mob);
                if (this.mob.getBlockX() == Mth.floor(next.x) && this.mob.getBlockY() == Mth.floor(next.y) && this.mob.getBlockZ() == Mth.floor(next.z)) {
                    this.path.advance();
                }
            }
            if (!this.isDone()) {
                assert this.path != null;
                var next = this.path.getNextEntityPos(this.mob);
                this.mob.getMoveControl().setWantedPosition(next.x, next.y, next.z, this.speedModifier);
            }
        }
    }

    @Override
    public boolean isStableDestination(BlockPos pos) {
        return this.level.getBlockState(pos).entityCanStandOn(this.level, pos, this.mob);
    }
}
