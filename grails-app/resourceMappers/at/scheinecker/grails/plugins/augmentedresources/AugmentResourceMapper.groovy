package at.scheinecker.grails.plugins.augmentedresources

import org.apache.commons.io.IOUtils
import org.apache.commons.logging.LogFactory
import org.grails.plugin.resource.mapper.MapperPhase
import org.springframework.core.io.Resource
import org.springframework.util.AntPathMatcher

/**
 * This resource mapper appends and prepends the content of files to configured resources.
 * @author Thomas Scheinecker
 */
class AugmentResourceMapper {
	private static final LOG = LogFactory.getLog(this)

	def grailsApplication

	def resourceService

	def phase = MapperPhase.GENERATION

	// -1 to ensure running before lesscss resources
	int priority = -1

	static defaultIncludes = ['less/**/*.less', 'css/**/*.css', 'js/**/*.js']

	AntPathMatcher antPathMatcher = new AntPathMatcher()

	private List<String> getMatchingPatterns(patterns, String sourceUrl) {
		List<String> matches = []

		String url = sourceUrl

		if (sourceUrl.startsWith('/')) {
			url = sourceUrl.substring(1)
		}

		patterns.each {
			if (antPathMatcher.match(it, url)) {
				matches << it
			}
		}

		return matches
	}

	private boolean copyResources(resources, Appendable appendable) {
		boolean copied = false
		resources.each {
			!it ?: (copied |= copyResource(it, appendable))
		}
		return copied
	}


	private boolean copyResource(Resource resource, Appendable appendable) {
		if (resource.exists()) {
			debug "Copy contents of ${resource}"
			def out = new ByteArrayOutputStream()
			IOUtils.copy(resource.inputStream, out)
			appendable << "${new String(out.toByteArray())}\n"
			return true
		}

		debug "Skipping ${resource} because it doesn't exist"
		return false
	}

	private boolean copyFile(File file, Appendable appendable) {
		if (file.exists()) {
			debug "Copy contents of ${file}"
			file.findAll().each {
				appendable << "${it}\n"
			}
			return true
		}

		debug "Skipping ${file} because it doesn't exist"
		return false
	}

	private add(list, toAdd) {
		if (toAdd) {
			if (toAdd instanceof Collection) {
				toAdd.findAll().each {
					list << getResource(it)
				}
			} else {
				list << getResource(toAdd)
			}
		}
	}

	def map(resource, config) {

		List<String> patterns = getMatchingPatterns(config.augment.keySet(), resource.sourceUrl)

		if (!patterns) {
			return // nothing to be done here
		}

		debug "Preparing to augment ${resource.sourceUrl}"

		List<Resource> prepends = []
		List<Resource> appends = []

		patterns.each {
			Object augmentConfig = config.augment[it]

			add prepends, augmentConfig.prepend
			add appends, augmentConfig.append
		}

		if (!prepends && !appends) {
			return // nothing to be done here
		}

		File origin = resource.processedFile

		File augmentFolder = new File(resourceService.workDir as File, 'augmented')
		File targetDir = new File(augmentFolder, resource.processedFileExtension)

		File target = new File(targetDir,
				replaceFileExtension(origin.name, "augmented.${resource.processedFileExtension}"))

		if (!targetDir.exists()) {
			debug "Creating new folder ${targetDir}"
			if (!targetDir.mkdirs()) {
				LOG.error "Failed to create folder ${targetDir} - resource augmentation aborted!"
				return
			}
		}

		if (target.exists()) {
			debug "${target.path} alread existst - trying to delete"
			if (!target.delete()) {
				LOG.error "Failed to delete ${target} - resource augmentation aborted!"
				return
			}
		}

		StringWriter stringWriter = new StringWriter()
		boolean copied = false

		!prepends ?: debug("Prepending...")
		copied |= copyResources(prepends, stringWriter)

		debug("Copy original...")
		copyFile(origin, stringWriter)

		!appends ?: debug("Appending...")
		copied |= copyResources(appends, stringWriter)

		if (!copied) {
			debug "Nothing was augmented - leaving the original resource untouched"
			return
		}

		debug "Writing augmented file content to ${target}"
		target << stringWriter.toString()

		resource.processedFile = target
		resource.updateActualUrlFromProcessedFile()

		if (config.lesscsscompatibility && resource.processedFileExtension == 'less') {
			// this part is completely messed up and is only here because the lesscss resources plugin
			// uses the sourceUrl instead of the processed file ...
			File original = getResource(resource.sourceUrl).file
			File copy = new File(original.parentFile, target.name)
			if (copy.exists()) {
				debug "Compatiblilty file ${copy} already exists - trying to delete"
				if (!copy.delete()) {
					LOG.error "Compatibility file couldn't be deleted - aborting"
					return
				}
			}

			debug "Writing augmented file content to compatibility file ${target}"
			copy << stringWriter.toString()
			resource.sourceUrl = "${resource.sourceUrl.replaceAll(original.name, target.name)}"

			resource.processedFile = copy
			resource.updateActualUrlFromProcessedFile()
		}
	}

	private Resource getResource(path) {
		if (path instanceof Resource) {
			return path
		}
		return grailsApplication.parentContext.getResource(path)
	}

	private String replaceFileExtension(String fileName, String extension) {
		String withoutExtension = fileName.subSequence(0, fileName.lastIndexOf('.'))
		return "${withoutExtension}.${extension}"
	}

	private static void debug(String message) {
		!LOG.debugEnabled ?: LOG.debug(message)
	}
}
