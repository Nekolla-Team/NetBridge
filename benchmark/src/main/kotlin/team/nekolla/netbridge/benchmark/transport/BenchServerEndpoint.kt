package team.nekolla.netbridge.benchmark.transport

interface BenchServerEndpoint : AutoCloseable {

    val port: Int

    override fun close()

}
