export default function CarAppServicePage() {
  return (
    <div className="mdx-content max-w-3xl">
      <h1>CarAppService Basics</h1>
      <p>
        <code>CarAppService</code> is your entry point for building navigation, parking,
        and EV charging apps that render natively on the in-vehicle display using the Car App Library.
      </p>

      <h2>Dependencies</h2>
      <pre><code>{`dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-automotive:1.4.0")
}`}</code></pre>

      <h2>Declaring the service</h2>
      <pre><code>{`<service
    android:name=".MyCarAppService"
    android:exported="true">
  <intent-filter>
    <action android:name="androidx.car.app.CarAppService" />
    <category android:name="androidx.car.app.category.NAVIGATION" />
  </intent-filter>
</service>`}</code></pre>

      <h2>Implementing the session</h2>
      <pre><code>{`class MyCarAppService : CarAppService() {

    override fun createHostValidator() =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession() = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            MapScreen(carContext)
    }
}`}</code></pre>
    </div>
  )
}
