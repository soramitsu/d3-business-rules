@Library('jenkins-library@feature/registryForTests' ) _
new org.bakong.mainLibrary().call(
    registry:'https://nexus.iroha.tech:19002', 
    nexusUserId: 'nexus-d3-docker', 
    registryForTests: 'https://nexus.iroha.tech:19002', 
    credentialsForTests: 'nexus-d3-docker',
    dockerTags: ['master': 'latest', 'develop': 'develop']
)