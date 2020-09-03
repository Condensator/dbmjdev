@Grab('org.apache.httpcomponents:httpclient:4.2.6')
// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*
import org.json.*
import groovyx.net.http.*

@groovy.transform.Field
def parameters = [jarPath: "", projectName: "", rsEnvName: "", authType: "", userName: "", authToken: "", server: "", packageDir: "", rsSchemaName: "", packagePrefix: "", \
				  wsURL: "", wsUserName: "", wsPassword: "", wsUseHttps: false, useZipPackaging: false, archiveArtifact: false, fileFilter: "Database\\*.sql", packageHintPath: "", \
				  driftDashboard: [[name: "DBMAESTRO_PIPELINE", environments: ["RS", "QA", "UAT"]], [name: "DBMAESTRO_PIPELINE", environments: ["RS", "QA", "UAT"]]]]

// Capture stdout lines, strip first line echo of provided command
def execCommand(String script) {
	def stdoutLines = ""
	def outList = []
	if (!parameters.isLinux) {
		echo "Executing windows command: ${script}"
		stdoutLines = bat([returnStdout: true, script: script])
	} else {
		echo "Executing linux command: ${script}"
		stdoutLines = sh([returnStdout: true, script: script])
	}
	echo stdoutLines

	if (!stdoutLines || stdoutLines.size() == 0)
		return outList

	outList = stdoutLines.trim().split("\n").collect {!parameters.isLinux ? it.replace("/", "\\") : it}
	return (outList.size() > 1) ? outList[1..-1] : []
}

def testGetPlugins(){
	def plugins = jenkins.model.Jenkins.instance.getPluginManager().getPlugins()
	plugins.each {println "${it.getShortName()}: ${it.getVersion()}"}
}


def findActionableFiles(String commit) {
	echo "Finding actionable file changes in ${commit}"
	return execCommand("git diff --name-only --diff-filter=AM ${commit}~1..${commit} -- ${parameters.fileFilter}")
}

def findPackageHintInCommit(String commit) {
	echo "Looking for package hint in ${commit}"
	return execCommand("git diff --name-only --diff-filter=AM ${commit}~1..${commit} -- ${parameters.packageHintPath}")
}

def getPackageHint(String commit) {
	def returnList = []
	if (!parameters.packageHintPath || parameters.packageHintPath.size() < 1) return returnList

	def fileList = findPackageHintInCommit(commit)
	if (!fileList || fileList.size() < 1) return returnList

	def inputPackage = new File("${env.WORKSPACE}\\${fileList[0]}")
	def packageHint = new JsonSlurper().parseText(inputPackage.text)
	packageHint.scripts.each { item -> returnList.add([ filePath: item.name])}
	return returnList
}

@NonCPS
def sortScriptsForPackage(List<Map> scriptsForPackage) {
	return scriptsForPackage.toSorted { a, b -> a.modified.compareTo(b.modified) }
}

// For use in determining issues in the WORKSPACE
@NonCPS
def EVTest() {
	echo "Working dir is ${env.WORKSPACE}"
	def workspaceDir = new File("${env.WORKSPACE}")
	workspaceDir.eachFileRecurse() {
		file -> 
			println file.getAbsolutePath()
	}
}

// Wrapped as noncps because of serialization issues with JsonBuilder
@NonCPS
def createPackageManifest(String name, List<String> scripts) {
	def manifest = new JsonBuilder()
	manifest name: name, operation: "create", type: "regular", enabled: true, closed: false, tags: [], scripts: scripts
	echo "Generating manifest:"
	def manifestOutput = manifest.toPrettyString()
	return manifestOutput
}

// Walk git history for direct commit or branch merge and compose package from SQL contents
def prepPackageFromGitCommit() {
	def scriptsForPackage = getPackageHint("HEAD")
	if (scriptsForPackage.size() < 1) {
		echo "gathering sql files from Database directory modified or created in the latest commit"
		def fileList = findActionableFiles("HEAD")
		//def fileList = execCommand("git diff --name-status HEAD~1..HEAD ${fileFilter}")
		if (!fileList || fileList.size() < 1) return
		echo "found " + fileList.size() + " sql files"
		for (filePath in fileList) {
			fileDate = new Date(new File("${env.WORKSPACE}\\${filePath}").lastModified())
			echo "File (${filePath}) found, last modified ${fileDate}"
			scriptsForPackage.add([ filePath: filePath, modified: fileDate, commit: [:] ])
		}
		
		if (scriptsForPackage.size() < 1) return
		
		echo "Getting parents of the current HEAD"
		def parentList = execCommand("git log --pretty=%%P -n 1")
		if (parentList.size() < 1) return

		echo "Parent git hash(es) found: ${parentList}"
		def parents = parentList[0].split(" ")
		
		if (parents.size() > 1) {
			def cherryCmd = "git cherry -v ${parents[0]} ${parents[1]}"
			echo "Commit is result of merge; finding branch history with git cherry command: ${cherryCmd}"
			def commitLines = execCommand(cherryCmd)
			commitLines.each { line -> echo(line) }
			for (commitLine in commitLines) {
				def details = commitLine.split(" ")
				def commitType = details[0]
				def commitHash = details[1]
				// def commitDesc = details[2..-1].join(" ")
				def commitDate = new Date(execCommand("git show --pretty=%%cd ${commitHash}")[0])
				def commitMail = execCommand("git show --pretty=%%ce ${commitHash}")[0]
				echo "Ancestor commit found: ${commitType} ${commitDate} ${commitHash} ${commitMail}" // ${commitDesc}
				
				echo "Finding files associated with commit ${commitHash}"
				def changedFiles = findActionableFiles(commitHash)
				//def changedFiles = execCommand("git diff --name-only ${commitHash}~1..${commitHash} ${fileFilter}")
				for (changedFile in changedFiles) {
					scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
					scriptForPackage.modified = commitDate
					scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail] // commitDesc: commitDesc, 
					echo "File (${scriptForPackage.filePath}) updated in ${scriptForPackage.commit.commitHash} on ${scriptForPackage.modified} by ${scriptForPackage.commit.commitMail}"
				}
			}
		} else {
			echo "Direct commit found; acquiring commit details"
			def commitType = "+"
			def commitHash = execCommand("git show --pretty=%%H")[0]
			def commitDate = new Date(execCommand("git show --pretty=%%cd")[0])
			def commitMail = execCommand("git show --pretty=%%ce")[0]

			echo "Finding files associated with commit ${commitHash}"
			def changedFiles = findActionableFiles("HEAD")
			//def changedFiles = execCommand("git diff --name-only HEAD~1..HEAD ${fileFilter}")
			for (changedFile in changedFiles) {
				scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
				scriptForPackage.modified = commitDate
				scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail] // commitDesc: commitDesc, 
				echo "File (${scriptForPackage.filePath}) updated in ${scriptForPackage.commit.commitHash} on ${scriptForPackage.modified} by ${scriptForPackage.commit.commitMail}"
			}
		}
		scriptsForPackage = sortScriptsForPackage(scriptsForPackage)
	} else {
		echo "found package manifest prepared at ${parameters.packageHintPath}"
	}
	
	def version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	echo "Preparing package ${version}"
	def dbm_artifact_dir = "${env.WORKSPACE}\\dbmartifact"
	dir (dbm_artifact_dir) {
		deleteDir()
	} 
	def version_dir = "${dbm_artifact_dir}\\${version}"
	def target_dir = "${version_dir}\\${parameters.rsSchemaName}"
	// new File(target_dir).mkdirs()

	def scripts = []
	for (item in scriptsForPackage) {
		def scriptFileName = item.filePath.substring(item.filePath.lastIndexOf("\\") + 1)
		// , tags: [[tagNames: [item.commit.commitMail, item.commit.commitHash], tagType: "Custom"]]
		scripts.add([name: scriptFileName])
		echo "Added ${item.filePath} to package staging and manifest"
		
		def isPrepared = fileExists target_dir
		if (!isPrepared) 
			bat "mkdir \"${target_dir}\""
		bat "copy /Y \"${env.WORKSPACE}\\${item.filePath}\" \"${target_dir}\""
	}
	def manifestOutput = createPackageManifest(version, scripts)
	echo manifestOutput

	dir(version_dir) {
		echo "writing to \"${version_dir}\\package.json\""
		writeFile file: "package.json", text: manifestOutput
	}

	def zipFileName = "${version}.dbmpackage.zip"
	echo "writing ${zipFileName}"
	zip archive: parameters.archiveArtifact, zipFile: zipFileName, dir: version_dir
	
	if (!parameters.useZipPackaging) {
		bat "mkdir \"${parameters.packageDir}\\${version}\""
		bat "xcopy \"${version_dir}\\*.*\" \"${parameters.packageDir}\\${version}\" /E /I /F /R"
	}

	echo 'Tada!'
}

def createPackage() {
	zippedPackagePath = "${env.WORKSPACE}\\${parameters.packagePrefix}${env.BUILD_NUMBER}.dbmpackage.zip"
	stagedPackagePath = "${parameters.packageDir}\\${parameters.packagePrefix}${env.BUILD_NUMBER}\\package.json"
	
	echo "locating ${zippedPackagePath}"
	zippedPackageExists = fileExists zippedPackagePath
	echo zippedPackageExists ? "...found" : "...not found"
	
	echo "locating ${stagedPackagePath}"
	stagedPackageExists = fileExists stagedPackagePath
	echo stagedPackageExists ? "...found" : "...not found"
	
	stuffToDo = zippedPackageExists || stagedPackageExists
	if (!stuffToDo) return

	if (!parameters.useZipPackaging) {
		bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
	}
	else {
		bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -FilePath ${parameters.packagePrefix}${env.BUILD_NUMBER}.dbmpackage.zip -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
	}
}

def upgradeReleaseSource() {
	bat "java -jar \"${parameters.jarPath}\" -Upgrade -ProjectName ${parameters.projectName} -EnvName ${parameters.rsEnvName} -PackageName ${parameters.packagePrefix}${env.BUILD_NUMBER} -Server ${parameters.server} -AuthType ${parameters.authType} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}

@NonCPS
def createBearerTokenPayload() {
	def payload = new JsonBuilder()
	payload grant_type: "password", username: parameters.wsUserName, password: parameters.wsPassword
	return payload.toString()
}

def acquireBearerToken() {
	def url = ((parameters.wsUseHttps) ? "https://" : "http://") + parameters.wsURL + "/Security/Token"
	def post = new URL(url).openConnection() as HttpURLConnection
	//def message = createBearerTokenPayload()
	post.setRequestMethod("POST")
	post.setDoInput(true)
	post.setDoOutput(true)
	post.setRequestProperty("Content-Type", "application/json")
	//echo message
	JSONObject payload = new JSONObject()
	payload.put("grant_type", "password")
	payload.put("username", parameters.wsUserName)
	payload.put("password", parameters.wsPassword)
	echo payload.toString()

	OutputStreamWriter writer = new OutputStreamWriter(post.getOutputStream())
	writer.write(URLEncoder.encode(payload.toString()))
	writer.flush()

	echo "Authorization response code: ${post.responseCode}"
	echo "Response: ${post.inputStream.text}"
	if (post.responseCode >= 400 && post.responseCode < 500) {
		echo "Unauthorized. Exiting..."
		return ""
	}

	if (!post.responseCode.equals(200)) {
		echo "Communications failure during authorization"
		return ""
	}

	echo "Authorization response: ${post.inputStream.text}"

	return post.inputStream.text
}

def composePackage() {
	//def bearerToken = acquireBearerToken()
	//echo bearerToken
/*
	def http = new HTTPBuilder(((parameters.wsUseHttps) ? "https://" : "http://") + parameters.wsURL + "/Security/Token")
	http.request(POST) {
		//uri.path = ((parameters.wsUseHttps) ? "https://" : "http://") + parameters.wsURL + "/Security/Token"
		requestContentType = ContentType.JSON
		body = [grant_type: "password", username: parameters.wsUserName, password: parameters.wsPassword]
		response.success = { resp ->
			println "Success! ${resp.status}"
		}
		response.failure = { resp ->
			println "Request failed with status ${resp.status}"
		}
	}
	*/
}

def generateDriftDashboard() {
	def reportBuffer = ''<<''
	reportBuffer << "<!DOCTYPE html><html><head><title>Drift Dashboard - 12/31/2020</title></head><body>"

	if (parameters.driftDashboard.size() < 1) return

	echo "Generating Drift Dashboard for ${parameters.driftDashboard.size()} pipelines..."
	for(pipeline in parameters.driftDashboard) {
		reportBuffer << "<h1>Pipeline: ${pipeline.name}</h1><font face=\"Courier New\" size=\"12\"><table><tr>"
		echo "Searching for drift in configured environments for ${pipeline.name}"
		for(environment in pipeline.environments) {
			def result = []
			def envName = environment.replace(" ", "")
			def outFile = "${pipeline.name}-${envName}-Validate-${env.BUILD_NUMBER}.txt"
			def script = "java -jar \"${parameters.jarPath}\" -Validate -ProjectName ${pipeline.name} -EnvName \"${environment}\" -PackageName @CurrentVersion -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken} >> ${outFile}"
			echo "Executing command: ${script}"
			bat returnStatus: true, script: script
			def stdoutLines = new File("${env.WORKSPACE}\\${outFile}").text
			echo stdoutLines
			def outList = stdoutLines.trim().split("\n").collect {it}

			def itsGood = false
			def url = "http://${parameters.server}:88"

			for (line in outList) {
				if (line.contains("[Report]")) {
					url = line.substring(line.indexOf("[Report]") + 8)
				}
				if (line.contains("[Job Status]")) {
					itsGood = line.contains("[Complete]")
				}
			}
			def statusColor = itsGood ? 'green' : 'red'

			reportBuffer << "<td bgcolor=\"${statusColor}\"><a href=\"${url}\">${environment}</a></td>"
		}
		reportBuffer << "</tr></table></font>"
	}
	reportBuffer << "</body></html>"
	if (reportBuffer.size() > 0) {
		def reportDate = (new Date()).format('M-d-yyyy')
		def reportFile = "DriftDashboard-${reportDate}-${env.BUILD_NUMBER}.html"
		echo "Preparing drift dashboard ${reportFile}"
		writeFile file: reportFile, text: reportBuffer.toString()
		archiveArtifacts artifacts: reportFile, fingerprint: true
	}
}
