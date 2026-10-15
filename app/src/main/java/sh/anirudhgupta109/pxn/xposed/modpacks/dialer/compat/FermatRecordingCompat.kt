package sh.anirudhgupta109.pxn.xposed.modpacks.dialer.compat

import android.annotation.SuppressLint
import android.content.Context
import io.github.libxposed.api.XposedInterface
import java.util.function.BooleanSupplier
import org.luckypray.dexkit.DexKitBridge
import sh.anirudhgupta109.pxn.xposed.utils.toolkit.Logger

object FermatRecordingCompat {
	@SuppressLint("DiscouragedApi")
	@JvmStatic
	fun install(context: Context, loader: ClassLoader,
		removeAnnouncement: BooleanSupplier, xposed: XposedInterface) {
		if (!removeAnnouncement.asBoolean) return
		val hooks = mutableListOf<XposedInterface.HookHandle>()
		try {
			val resources = listOf("fermat_call_recording_settings_title",
				"fermat_call_recording_auto_start_prompt_content_title",
				"incall_snackbar_call_recording_start_error").map {
				context.resources.getIdentifier(it, "string", "com.google.android.dialer")
			}
			if (resources.any { it == 0 }) return
			System.loadLibrary("dexkit")
			DexKitBridge.create(loader, false).use { bridge ->
				val targets = FermatRecordingTargets.resolve(bridge, resources)
				val feature = targets.feature.getFieldInstance(loader).apply { isAccessible = true }
				val flag = targets.playPrompts.getFieldInstance(loader).apply { isAccessible = true }
				val recording = Class.forName(targets.recordingClass, false, loader)
				val methods = targets.prompts.map { it.getMethodInstance(loader) }
				require(feature.type.isAssignableFrom(recording) && flag.type == Boolean::class.javaPrimitiveType)
				methods.forEach { check(xposed.deoptimize(it)) }
				methods.forEach { method ->
					hooks += xposed.hook(method).intercept { chain ->
						if (removeAnnouncement.asBoolean && recording.isInstance(feature.get(chain.thisObject))) {
							// Startup resets this flag, so change it at each prompt entry.
							flag.setBoolean(chain.thisObject, false)
						}
						chain.proceed()
					}
				}
			}
			Logger.log("PixelXpert: recording prompt hooks installed")
		} catch (error: Throwable) {
			hooks.forEach { runCatching { it.unhook() } }
			Logger.log("PixelXpert: recording prompt lookup failed; keeping legacy hooks: $error")
		}
	}
}
