@Grab('org.apache.httpcomponents:httpclient:4.2.6')
// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*
import org.json.*
import groovyx.net.http.*

@groovy.transform.Field
def parameters = [jarPath: "", projectName: "", rsEnvName: "", authType: "", userName: "", authToken: "", server: "", packageDir: "", rsSchemaName: "", packagePrefix: "", \
				  proxyPath: "", wsURL: "", wsUserName: "", wsPassword: "", wsUseHttps: false, useZipPackaging: false, archiveArtifact: false, fileFilter: "Database\\*.sql", packageHintPath: "", packageType:"regular", \
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
def createPackageManifest(String name, List<String> scripts, String packageType="regular") {
	def manifest = new JsonBuilder()
	echo "Package type value"
	echo parameters.packageType
	manifest name: name, operation: "create", type: packageType, enabled: true, closed: false, tags: [], scripts: scripts
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
	if (!parameters.packageType?.trim()) {
  		parameters.packageType="regular"
	}
	def manifestOutput = createPackageManifest(version, scripts, parameters.packageType)
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

def runPackageCommand() {
	execCommand("java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}")
}

def uploadByProxy() {
	def version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	def zipFileName = "${version}.dbmpackage.zip"
	execCommand("curl -F \"packageFile=@${zipFileName}\" -X POST ${parameters.proxyPath}")
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

@NonCPS
def checkPluginInstalled(String pluginName)
{
	def pluginsInstalled = jenkins.model.Jenkins.instance.getPluginManager().getPlugins()
	return pluginsInstalled.any { it.getShortName()==pluginName}
}

def generateDriftDashboard() {
	prepareReportFiles()
	def reportDate = (new Date()).format('M-d-yyyy')
	def reportName = "DriftDashboard-${reportDate}-${env.BUILD_NUMBER}"
	def reportBuffer = ''<<''
	reportBuffer << "<!DOCTYPE html><html><head><title>${reportName}</title><style>body {font-family: Arial;}table {border: 1px solid #7297D0;font-size: 12px;}table tr:first-child td {border-bottom: 1px dashed #7297D0;color: #18309E;font-weight: bold;border-radius: 4px;overflow: hidden;}td {padding: 10px;}p {border-bottom: 1px dashed #7297D0;color: #18309E;font-weight: bold;display: inline-block;}table tr:nth-child(2n) td {background: #F7F7F7;}footer a {color: #188AD7;text-decoration: none;font-weight: bold;}</style></head><body style='font-family: Arial;'>"
	reportBuffer << "<div><img src='reportfiles/img.png' alt='DBMaestro logo'></div>"
	reportBuffer << "<p style='border-bottom: 1px dashed #7297D0;color: #18309E;font-weight: bold;display: inline-block;'>Report generated on 27/07/2020 09:44</p><br>"

	if (parameters.driftDashboard.size() < 1) return

	echo "Generating Drift Dashboard for ${parameters.driftDashboard.size()} pipelines..."
	for(pipeline in parameters.driftDashboard) {
		reportBuffer << "<p style='border-bottom: 1px dashed #7297D0;color: #18309E;font-weight: bold;display: inline-block;'>Pipeline: ${pipeline.name}</p>"
		reportBuffer << "<table border='0' cellpadding='0' cellspacing='0' style='border: 1px solid #7297D0;font-size: 12px;border-radius:4px; overflow: hidden;'><tr align='left' valign='top'>"
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
			def itsBad = false
			def url = "http://${parameters.server}:88"

			for (line in outList) {
				if (line.contains("[Report]")) {
					url = line.substring(line.indexOf("[Report]") + 9, line.length() - 1)
					echo "Drift report link found at: ${url}"
				}
				if (line.contains("[Job Status]")) {
					itsGood = line.contains("[Complete]")
					echo "Successful validation detected"
				}
				if (line.contains("[Server Message] Failed")) {
					itsBad = true
					echo "Pipeline error detected"
				}
			}
			def statusColor = itsBad ? 'red' : itsGood ? 'green' : 'yellow'

			reportBuffer << "<td align='left' valign='top' bgcolor='${statusColor}' style='padding: 10px;border-bottom:1px dashed #7297D0; color: #18309E; font-weight: bold;'> <a href='${url}'> ${environment}</a></td>"
		}
		reportBuffer << "</tr></table><br />"
	}
	reportBuffer << "<footer>Powered by <a href='http://www.dbmaestro.com/' style='color: #188AD7;font-weight:bold;'>DBmaestro</a></footer></body></html>"
	if (reportBuffer.size() > 0) {
		def reportFile = "${reportName}.html"
		echo "Preparing drift dashboard ${reportFile}"
		writeFile file: reportFile, text: reportBuffer.toString()
		if(checkPluginInstalled("htmlpublisher"))
		{
			publishHTML target : [allowMissing: false,
 				alwaysLinkToLastBuild: true,
 				keepAll: true,
				reportDir: '.',
				includes: "reportfiles\\*.*",
 				reportFiles: "${reportFile}",
 				reportName: 'Drift Dashboard',
 				reportTitles: 'Drift Dashboard']

		}
		archiveArtifacts artifacts: reportFile, fingerprint: true
		
	}
}

def prepareReportFiles()
{
	String img = "iVBORw0KGgoAAAANSUhEUgAAAO0AAAA6CAYAAABcdt4YAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyBpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuMC1jMDYwIDYxLjEzNDc3NywgMjAxMC8wMi8xMi0xNzozMjowMCAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIiB4bWxuczp4bXBNTT0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL21tLyIgeG1sbnM6c3RSZWY9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZVJlZiMiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENTNSBXaW5kb3dzIiB4bXBNTTpJbnN0YW5jZUlEPSJ4bXAuaWlkOjQwMTIwNEFDNTQ5RDExRTU4NThBQkY5RDFENTQ1QTEyIiB4bXBNTTpEb2N1bWVudElEPSJ4bXAuZGlkOjQwMTIwNEFENTQ5RDExRTU4NThBQkY5RDFENTQ1QTEyIj4gPHhtcE1NOkRlcml2ZWRGcm9tIHN0UmVmOmluc3RhbmNlSUQ9InhtcC5paWQ6NDAxMjA0QUE1NDlEMTFFNTg1OEFCRjlEMUQ1NDVBMTIiIHN0UmVmOmRvY3VtZW50SUQ9InhtcC5kaWQ6NDAxMjA0QUI1NDlEMTFFNTg1OEFCRjlEMUQ1NDVBMTIiLz4gPC9yZGY6RGVzY3JpcHRpb24+IDwvcmRmOlJERj4gPC94OnhtcG1ldGE+IDw/eHBhY2tldCBlbmQ9InIiPz5JNDnYAAAltElEQVR42uxdB5gc1ZGu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"
	String styles = "body{font-family:Arial}table{border:1px solid #7297d0;font-size:12px}table tr:first-child td{border-bottom:1px dashed #7297d0;color:#18309e;font-weight:700;border-radius:4px;overflow:hidden}td{padding:10px}p{border-bottom:1px dashed #7297d0;color:#18309e;font-weight:700;display:inline-block}table tr:nth-child(2n) td{background:#f7f7f7}footer a{color:#188ad7;text-decoration:none;font-weight:700}"
	def dbm_report_files_dir = "${env.WORKSPACE}\\reportfiles"
	dir (dbm_shared_dir) {
		deleteDir()
	}
	def isPrepared = fileExists dbm_report_files_dir
		if (!isPrepared) 
		bat "mkdir \"${dbm_report_files_dir}\""
	dir(dbm_report_files_dir) {
		echo "writing to \"${dbm_report_files_dir}\\img.png\""
		new File("${dbm_report_files_dir}\\img.png").bytes = Base64.getDecoder().decode(img)
		echo "writing to \"${dbm_report_files_dir}\\style.css\""
		new File("${dbm_report_files_dir}\\style.css").write(styles)
	}
}
