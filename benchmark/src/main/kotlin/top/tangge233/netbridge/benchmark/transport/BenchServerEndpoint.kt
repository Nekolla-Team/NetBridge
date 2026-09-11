package top.tangge233.netbridge.benchmark.transport

interface BenchServerEndpoint : AutoCloseable {

    val port: Int

    override fun close()

}
