package ai.minecivilization.client;

import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityAttachment;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Citizen renderer: vanilla humanoid model with the default skin, plus a
 * two-line name tag (name on top, profession underneath).
 */
public final class CitizenRenderer
        extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return DefaultPlayerSkin.getDefaultTexture();
    }

    @Override
    protected void renderNameTag(CitizenEntity entity, Component name, PoseStack pose,
                                 MultiBufferSource buffer, int packedLight, float partialTick) {
        super.renderNameTag(entity, name, pose, buffer, packedLight, partialTick);

        String profession = entity.getIdentity().profession;
        if (profession == null || profession.isBlank() || "UNASSIGNED".equals(profession)) {
            return;
        }
        var attachment = entity.getAttachments().getNullable(
                EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (attachment == null) {
            return;
        }

        // Mirror the vanilla name tag transform, then draw one line underneath.
        Component professionLine = Component.literal(profession);
        pose.pushPose();
        pose.translate(attachment.x, attachment.y + 0.5, attachment.z);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);

        Font font = this.getFont();
        float x = -font.width(professionLine) / 2.0F;
        float y = 10.0F; // one text line below the name
        float backgroundOpacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int background = (int) (backgroundOpacity * 255.0F) << 24;

        var matrix = pose.last().pose();
        font.drawInBatch(professionLine, x, y, 553648127, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, background, packedLight);
        font.drawInBatch(professionLine, x, y, -1, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }
}
