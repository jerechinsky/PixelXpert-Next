package sh.anirudhgupta109.pxn.xposed.modpacks.dialer.compat

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

internal data class FermatRecordingTargets(
	val prompts: List<MethodData>,
	val playPrompts: FieldData,
	val feature: FieldData,
	val recordingClass: String,
) {
	companion object {
		fun resolve(bridge: DexKitBridge, recordingResourceIds: List<Int>): FermatRecordingTargets {
			require(recordingResourceIds.size == 3 && recordingResourceIds.all { it != 0 })
			val cue = bridge.findMethod {
				matcher {
					usingStrings("com/android/dialer/fermat/impl/audioprism/FermatSession",
						"playBeforeDisclaimerAudioCue", "Beep muted.")
					returnType = "java.lang.Object"
					paramCount = 1
				}
			}.single()
			val session = requireNotNull(cue.declaredClass)
			// The countdown and both announcements share a native skip-prompts flag.
			// Resolve it from the anchored countdown, never from an obfuscated name.
			val flag = cue.usingFields.filter {
				it.usingType == FieldUsingType.Read && it.field.className == session.name &&
					it.field.typeName == "boolean" && !Modifier.isStatic(it.field.modifiers)
			}.map { it.field }.distinctBy { it.descriptor }.single()
			val prompts = flag.readers.distinctBy { it.descriptor }
			require(prompts.size == 3 && prompts.any { it.descriptor == cue.descriptor })
			require(prompts.all {
				it.className == session.name && !Modifier.isStatic(it.modifiers) &&
					it.returnTypeName == "java.lang.Object" && it.paramTypeNames == cue.paramTypeNames &&
					it.usingFields.none { use -> use.usingType == FieldUsingType.Write &&
						use.field.descriptor == flag.descriptor }
			})
			// Start and stop must call the same (prompt kind, continuation) dispatcher.
			val announcements = prompts.filter { it.descriptor != cue.descriptor }
			val dispatchers = announcements.map { method ->
				method.invokes.filter {
					it.className == session.name && it.returnTypeName == "java.lang.Object" &&
						it.paramTypeNames == listOf("int", cue.paramTypeNames.single())
				}.map { it.descriptor }.toSet()
			}
			require(dispatchers.reduce { a, b -> a intersect b }.size == 1)

			// Resource IDs are resolved from the installed Phone resources. Intersect
			// three recording-only references so Call Notes cannot select this path.
			val recordingClasses = recordingResourceIds.map { id ->
				bridge.findMethod { matcher { usingNumbers(id) } }.map { it.className }.toSet()
			}.reduce { a, b -> a intersect b }
			val recordingClass = recordingClasses.single()
			val interfaces = bridge.findClass { matcher { className = recordingClass } }
				.single().interfaces.map { it.name }.toSet()
			val feature = session.fields.filter {
				!Modifier.isStatic(it.modifiers) && it.typeName in interfaces
			}.single()
			return FermatRecordingTargets(prompts, flag, feature, recordingClass)
		}
	}
}
