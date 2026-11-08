import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'
import LoveAppPage from '../views/LoveAppPage.vue'
import ManusPage from '../views/ManusPage.vue'

const routes = [
  { path: '/', component: HomePage },
  { path: '/love-app', component: LoveAppPage },
  { path: '/manus', component: ManusPage },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
