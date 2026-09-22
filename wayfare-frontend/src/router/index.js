import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'work/:id',
        name: 'WorkDetail',
        component: () => import('@/views/WorkDetail.vue'),
        meta: { title: '作品详情' }
      },
      {
        path: 'publish',
        name: 'WorkPublish',
        component: () => import('@/views/WorkPublish.vue'),
        meta: { title: '发布作品', requiresAuth: true }
      },
      {
        path: 'edit/:id',
        name: 'WorkEdit',
        component: () => import('@/views/WorkPublish.vue'),
        meta: { title: '编辑作品', requiresAuth: true }
      },
      {
        path: 'plan',
        name: 'Plan',
        component: () => import('@/views/Plan.vue'),
        meta: { title: 'AI 规划', requiresAuth: true }
      },
      {
        path: 'trips',
        name: 'MyTrips',
        component: () => import('@/views/MyTrips.vue'),
        meta: { title: '我的行程', requiresAuth: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        path: 'user/:id',
        name: 'UserHome',
        component: () => import('@/views/UserHome.vue'),
        meta: { title: '用户主页' }
      },
      {
        path: 'messages',
        name: 'Messages',
        component: () => import('@/views/Messages.vue'),
        meta: { title: '私信', requiresAuth: true }
      }
    ]
  },
  // 管理员后台
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'AdminDashboard',
        component: () => import('@/views/admin/Dashboard.vue'),
        meta: { title: '管理后台' }
      },
      {
        path: 'works',
        name: 'AdminWorks',
        component: () => import('@/views/admin/WorkManage.vue'),
        meta: { title: '作品管理' }
      },
      {
        path: 'users',
        name: 'AdminUsers',
        component: () => import('@/views/admin/UserManage.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'audit',
        name: 'AdminAudit',
        component: () => import('@/views/admin/Audit.vue'),
        meta: { title: '内容审核' }
      },
      {
        path: 'connectors',
        name: 'AdminConnectors',
        component: () => import('@/views/admin/Connectors.vue'),
        meta: { title: '连接器管理' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// 路由守卫
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 行走集` : '行走集'

  const userStore = useUserStore()

  // 公开页面直接放行
  if (to.meta.public) {
    next()
    return
  }

  // 需要登录
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 需要管理员权限
  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    next({ path: '/' })
    return
  }

  next()
})

export default router
