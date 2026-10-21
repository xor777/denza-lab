#!/usr/bin/env python3
"""Replay retained factory script-route evidence; offline, read-only, stdlib."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('factory-dtc-script-route-20261021.json')


def main():
    checks = 0

    def check(value, label):
        nonlocal checks
        assert value, label
        checks += 1

    def read_verified(item):
        data = (ROOT / item['path']).read_bytes()
        check(hashlib.sha256(data).hexdigest() == item['sha256'], item['path'])
        check(len(data) == item['bytes'], item['path'] + ' size')
        return data

    report = json.loads(REPORT.read_text())
    check(report['newQualifiedDefinitions'] == 0, 'no new definitions')
    check(report['selectedTuple'] is None, 'tuple remains missing')
    check(all(v == 0 for v in report['actions'].values()), 'offline boundary')
    frozen = json.loads(read_verified(report['evidence']['sourceManifest']))
    cache = json.loads(read_verified(report['evidence']['cacheFilenames']))
    read_verified(report['evidence']['builder'])
    read_verified(report['evidence']['reportText'])
    sources = {}
    ranges = 0
    for name, item in frozen['sources'].items():
        raw = read_verified(item)
        source = raw.decode()
        sources[name] = source
        lines = source.splitlines(keepends=True)
        for entry in item['ranges']:
            selected = ''.join(lines[entry['start'] - 1:entry['end']])
            check(selected == entry['text'], name + ' exact range')
            check(hashlib.sha256(selected.encode()).hexdigest() == entry['sha256'], name + ' range digest')
            ranges += 1
    for item in frozen['references'].values():
        read_verified(item)
    anchors = {
        'main_app': ['setMqttServiceReadyListener', 'checkEolInit();', 'BydMqttManagerHolder.getInstance().init(this)'],
        'mqtt_holder': ['bydMqttManager = BydMqttManager.getInstance()'],
        'mqtt_topics': ['EOL_DEV(21, 23, 1037, 1038)', 'EOL_PRODUCT_LINE(21, 23, 1037, 1038)'],
        'mqtt_manager': ['new MqttMessageReceiver(MqttTopic.EOL_PRODUCT_LINE)', 'registerListenerByFid(topic.getV2Read()', 'this.receiver.receiveMessage(bArr)', 'CommonUtils.keepSpotCheckSilence()', 'EolTaskManager.getInstance().stopTask()'],
        'mqtt_version': ['isDi6_0() || isDi5_1() || isDi300()'],
        'mqtt_receive': ['messageDecoder.decode(messageRequest)', 'executor.getRunner(messageBeanDecode)', 'runner.checkAndParse()', 'executor.execute(runner)'],
        'executor_v3': ['new DownloadScriptCommandRunner(header, data)', 'new ScriptRunCommandRunner(header, data)'],
        'task_lifecycle': ['if (this.taskInfo == null) {\n            clearTaskData();', 'SpUtils.getInstance(BaseApp.getAppContext()).remove(RUNNING_TASK_KEY)', 'ScriptDao.getInstance().clear()', 'this.sender.clearSendCache()', 'getAll().stream().allMatch(', 'stationSessionBean.getStage() == StationStage.FINISH', 'stopTask();'],
        'run_selection_finish': ['getContent(scriptRequest.getScriptCode(), scriptRequest.getScriptVersion(), scriptRequest.getForStation())', 'getNeedClearData()', 'syncStationScript(this.station, new ArrayList(0))', 'finishStation(this)'],
        'script_storage': ['if (CollectionUtils.isEmpty(list))', 'FileUtils.deleteDirectory(scriptDir.getAbsolutePath())', 'scriptDir.mkdir()', 'object.remove("data")'],
        'result_uploader': ['setForStation(scriptRequest.getForStation()).setScriptVersion(scriptRequest.getScriptVersion())', 'JSON.toJSONString(scriptRunReportInfo, SerializerFeature.PrettyFormat)', 'CmdTypeEnum.EOL_RESULT_REPORT_SYNC', 'CmdTypeEnum.RESULT_ENCRYPTION_EXCEPTION_SYNC', 'CmdTypeEnum.SCRIPT_PARSING_EXCEPTION_SYNC', 'CmdTypeEnum.SCRIPT_EXECUTION_EXCEPTION_SYNC'],
        'mqtt_send': ['MqttReportDao.getInstance().save(this.topic, jSONString)', 'MqttReportDao.getInstance().clear()', 'resendWithRetry(list, 1, 8)', 'eolHeader.getDataEncryPolicy().getAlgorithm().encrypt(str, eolHeader)'],
        'metadata_system': ['private String funcType;', 'private int priority;', 'private String systemId;'],
    }
    for name, values in anchors.items():
        for value in values:
            check(value in sources[name], name + ': ' + value)
    check(cache['matchingFiles'] == [], 'no named local caches')
    check(sum(cache['regularFileCounts'].values()) == 548155, 'frozen filename count')
    check(report['result']['observedProductionTask'] is False, 'source is not runtime')
    check(report['result']['observedCauseOfEmptyCache'] is None, 'cause remains unproved')
    print(json.dumps({'status': 'PASS', 'checks': checks, 'sources': len(sources),
                      'exactRanges': ranges, 'references': len(frozen['references']),
                      'localFilenameCount': sum(cache['regularFileCounts'].values()),
                      'newQualifiedDefinitions': 0, 'vehicleOperations': 0,
                      'networkOperations': 0}, indent=2))


if __name__ == '__main__':
    main()
